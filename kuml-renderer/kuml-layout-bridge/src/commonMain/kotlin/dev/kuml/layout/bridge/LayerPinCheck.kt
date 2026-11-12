package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.LayoutGraph

/** Number of edges that arrive at / leave a node, counted in the direction the layout engine sees (source to target). */
public data class EdgeDegree(
    val incoming: Int,
    val outgoing: Int,
)

/** Which side of the diagram `first()` / `last()` pins a node to. */
internal enum class PinSide { FIRST, LAST }

/**
 * Detects `first()` / `last()` pins that fight the edges of the node: a last-layer node that has outgoing edges
 * (or a first-layer node that has incoming edges) forces the engine to draw those edges against the flow
 * direction. The pin is still applied; the finding only tells the author. Used by the render path and by
 * `kuml validate`, so both count edges the same way.
 */
public object LayerPinCheck {
    /**
     * Edge counts per node id. Counts every edge of [graph] whose two endpoints are distinct nodes of the graph,
     * in the engine's source-to-target orientation (UML generalisations, `gen::` ids, count general -> specific). Self-loops and edges to groups or unknown ids do not take part
     * in layering and are not counted.
     */
    public fun edgeDegrees(graph: LayoutGraph): Map<String, EdgeDegree> {
        val nodeIds = graph.nodes.map { it.id.value }.toSet()
        val incoming = HashMap<String, Int>()
        val outgoing = HashMap<String, Int>()
        for (edge in graph.edges) {
            // Nacharbeit 85: the engine sees a UML generalisation general -> specific (the general class is drawn first).
            val flip = edge.id.value.startsWith("gen::")
            val s = (if (flip) edge.target else edge.source).nodeId.value
            val t = (if (flip) edge.source else edge.target).nodeId.value
            if (s == t || s !in nodeIds || t !in nodeIds) continue
            outgoing[s] = (outgoing[s] ?: 0) + 1
            incoming[t] = (incoming[t] ?: 0) + 1
        }
        return nodeIds.associateWith { EdgeDegree(incoming = incoming[it] ?: 0, outgoing = outgoing[it] ?: 0) }
    }

    /** `true` when a pin to [side] conflicts with the node's edges. */
    internal fun conflicts(
        side: PinSide,
        degree: EdgeDegree,
    ): Boolean =
        when (side) {
            PinSide.LAST -> degree.outgoing > 0
            PinSide.FIRST -> degree.incoming > 0
        }

    internal fun message(
        id: String,
        side: PinSide,
        degree: EdgeDegree,
    ): String =
        when (side) {
            PinSide.LAST ->
                "layout.last($id): '$id' has ${degree.outgoing} outgoing edge(s); a last-layer node is drawn after its " +
                    "targets, so the edge(s) run against the flow direction. Remove last($id) or the edge."
            PinSide.FIRST ->
                "layout.first($id): '$id' has ${degree.incoming} incoming edge(s); a first-layer node is drawn before " +
                    "its sources, so the edge(s) run against the flow direction. Remove first($id) or the edge."
        }

    /** One [LayoutCodes.LAYER_PIN_CONFLICT] per `first()` / `last()` id whose pin conflicts with its edges. */
    public fun findings(
        spec: LayoutSpec?,
        degrees: Map<String, EdgeDegree>?,
    ): List<LayoutSpecFinding> {
        if (spec == null || degrees == null) return emptyList()
        val out = ArrayList<LayoutSpecFinding>()
        for ((side, ids) in listOf(PinSide.FIRST to spec.first, PinSide.LAST to spec.last)) {
            for (id in ids.distinct()) {
                val degree = degrees[id] ?: continue
                if (conflicts(side = side, degree = degree)) {
                    out +=
                        LayoutSpecFinding(code = LayoutCodes.LAYER_PIN_CONFLICT, message = message(id = id, side = side, degree = degree))
                }
            }
        }
        return out
    }
}
