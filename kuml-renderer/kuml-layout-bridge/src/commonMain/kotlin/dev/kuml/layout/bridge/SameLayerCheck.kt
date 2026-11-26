package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId

/** Upper bound of nodes the path search of [SameLayerCheck] visits per group (hostile-input cap). */
private const val SAME_LAYER_MAX_VISITED = 4096

/**
 * `KUML-LAYOUT-W014` (PAL wave T): a `sameLayer` group whose members do not share a layer in the finished layout. The check
 * only reads the result; it never changes the layout. It gives the reason, found in this fixed order: a direct edge between
 * two members, a `first()` / `last()` pin that holds one member alone, a directed path between two members, otherwise the
 * plain statement. Edges withheld from the engine (members of one group connected by an edge are drawn straight by
 * [alignSameLayerCenters]) never count as the reason.
 */
internal object SameLayerCheck {
    fun findings(
        result: LayoutResult,
        groups: List<List<String>>,
        direction: LayoutDirection,
        graph: LayoutGraph,
        first: Set<String>,
        last: Set<String>,
        withheld: Set<EdgeId>,
    ): List<LayoutSpecFinding> {
        if (groups.isEmpty()) return emptyList()
        val vertical = direction == LayoutDirection.TopToBottom || direction == LayoutDirection.BottomToTop
        val out = ArrayList<LayoutSpecFinding>()
        for (group in groups) {
            val members = group.distinct().filter { NodeId(it) in result.nodes }
            if (members.size < 2) continue
            if (sharesLayerBand(
                    members = members.map { NodeId(it) },
                    nodes = result.nodes.mapValues { it.value.bounds },
                    vertical = vertical,
                )
            ) {
                continue
            }
            val head = "sameLayer(${group.joinToString(", ")}) could not be honoured: "
            val usable = graph.edges.filter { it.id !in withheld && it.source.nodeId != it.target.nodeId }
            val reason =
                directEdge(members = members, edges = usable)
                    ?: pin(members = members, first = first, last = last)
                    ?: path(members = members, edges = usable)
                    ?: "the members do not share a layer. Remove sameLayer."
            out +=
                LayoutSpecFinding(
                    code = LayoutCodes.SAME_LAYER_NOT_HONOURED,
                    message = head + reason,
                )
        }
        return out
    }

    private const val REMOVE_OR_REVERSE = "Remove sameLayer or remove/reverse the edge."

    /** Name shown for an edge: an IBD connector (`conn:path::I13`) shows its usage name, every other id is shown as it is. */
    private fun shown(id: EdgeId): String {
        val v = id.value
        return if (v.startsWith("conn:") || v.startsWith("flow:")) v.substringAfterLast("::").ifEmpty { v } else v
    }

    private fun directEdge(
        members: List<String>,
        edges: List<LayoutEdge>,
    ): String? {
        val set = members.toSet()
        val e = edges.filter { it.source.nodeId.value in set && it.target.nodeId.value in set }.minByOrNull { it.id.value } ?: return null
        return "edge ${shown(
            e.id,
        )} connects ${e.source.nodeId.value} and ${e.target.nodeId.value} and forces different layers. $REMOVE_OR_REVERSE"
    }

    private fun pin(
        members: List<String>,
        first: Set<String>,
        last: Set<String>,
    ): String? {
        val pinnedFirst = members.firstOrNull { it in first }
        if (pinnedFirst != null && members.any { it !in first }) {
            return "first($pinnedFirst) moves $pinnedFirst to the first layer. Remove sameLayer or the pin."
        }
        val pinnedLast = members.firstOrNull { it in last }
        if (pinnedLast != null && members.any { it !in last }) {
            return "last($pinnedLast) moves $pinnedLast to the last layer. Remove sameLayer or the pin."
        }
        return null
    }

    private fun path(
        members: List<String>,
        edges: List<LayoutEdge>,
    ): String? {
        val out = edges.sortedBy { it.id.value }.groupBy { it.source.nodeId.value }
        val sorted = members.sorted()
        for (a in sorted) {
            for (b in sorted) {
                if (a == b) continue
                val firstEdge = firstEdgeOnPath(from = a, to = b, out = out) ?: continue
                return "edge ${shown(firstEdge.id)} lies on a path from $a to $b and forces different layers. $REMOVE_OR_REVERSE"
            }
        }
        return null
    }

    private fun firstEdgeOnPath(
        from: String,
        to: String,
        out: Map<String, List<LayoutEdge>>,
    ): LayoutEdge? {
        val visited = HashSet<String>().apply { add(from) }
        val queue = ArrayDeque<Pair<String, LayoutEdge?>>()
        queue.add(from to null)
        while (queue.isNotEmpty() && visited.size <= SAME_LAYER_MAX_VISITED) {
            val (node, viaFirst) = queue.removeFirst()
            for (e in out[node].orEmpty()) {
                val next = e.target.nodeId.value
                if (!visited.add(next)) continue
                val start = viaFirst ?: e
                if (next == to) return start
                queue.add(next to start)
            }
        }
        return null
    }
}
