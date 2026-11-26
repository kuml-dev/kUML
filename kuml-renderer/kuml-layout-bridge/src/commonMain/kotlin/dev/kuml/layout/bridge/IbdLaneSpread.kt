package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.PortId
import dev.kuml.layout.Rect

/**
 * Separates IBD connectors that run side by side (PAL wave T, P-IBD-04). Two connectors that leave or enter two ports of
 * one part may share a corridor segment after the router has laid them out; the existing parallel lane spread of the STM
 * path is reused for them, with these differences:
 *
 *  * ports are fixed anchors (P-IBD-08): an end segment never moves, an inner segment moves with its two bends and the
 *    stubs next to it change length (never below `min(before, 12 px)`);
 *  * two connectors of the same port form a fan-out stem and stay on one line; two ports of one part are no exemption;
 *  * a move is rejected when it adds a crossing, hits more port squares, or pushes a bend closer to the frame;
 *  * connectors with a gate (nested frame) never move, but count as partners.
 *
 * A layout without a violation comes back as the **same object**. When two connectors run side by side as end stubs of
 * their ports (both end segments), nothing can be done: the ports stay where they are.
 */
internal object IbdLaneSpread {
    /** One connector of the diagram: [pinned] connectors (those with a gate) never move. */
    data class Wire(
        val edgeId: EdgeId,
        val source: EndpointRef,
        val target: EndpointRef,
        val pinned: Boolean,
    )

    /**
     * @param portSquares port squares (part ports and owner ports) by port key; they are obstacles for a moved bend
     * @param ownerPortNodes owner-port squares as nodes, keyed by node id; they are not part of [result]'s nodes yet
     * @param frame inner area the bends stay in (owner frame or canvas), `null` when there is none
     */
    fun apply(
        result: LayoutResult,
        wires: List<Wire>,
        portSquares: Collection<Rect>,
        ownerPortNodes: Map<NodeId, Rect> = emptyMap(),
        frame: Rect?,
    ): LayoutResult {
        val known = wires.filter { it.edgeId in result.edges }
        if (known.size < 2) return result
        val nodes = result.nodes + ownerPortNodes.mapValues { NodeLayout(bounds = it.value) }
        val work = result.copy(nodes = nodes)
        val graph =
            LayoutGraph(
                nodes = nodes.map { (id, nl) -> LayoutNode(id = id, intrinsicSize = nl.bounds.size) },
                edges = known.map { LayoutEdge(id = it.edgeId, source = it.source, target = it.target) },
            )
        val before = detectViolations(result = work, graph = graph, portAware = true).parallel.size
        if (before == 0 || !IbdLaneSpreadProbe.spreadEnabled) {
            IbdLaneSpreadProbe.record(before = before, after = before, changed = emptyList())
            return result
        }
        val spread =
            work.spreadParallelEdges(
                graph = graph,
                pinnedEndSegments = true,
                portAware = true,
                extraObstacles = portSquares.toList(),
                bounds = frame,
                fixedEdges = known.filter { it.pinned }.map { it.edgeId }.toSet(),
            )
        val after = detectViolations(result = spread, graph = graph, portAware = true).parallel.size
        val crossingsBefore = countCrossings(work.edges.values.mapNotNull { polylinePoints(it) })
        val crossingsAfter = countCrossings(spread.edges.values.mapNotNull { polylinePoints(it) })
        if (after >= before || crossingsAfter > crossingsBefore) {
            IbdLaneSpreadProbe.record(before = before, after = before, changed = emptyList())
            return result
        }
        val changed = known.map { it.edgeId }.filter { spread.edges[it] != work.edges[it] }
        IbdLaneSpreadProbe.record(before = before, after = after, changed = changed.map { it.value })
        return result.copy(edges = result.edges + changed.associateWith { spread.edges.getValue(it) })
    }

    /** Port key used as [EndpointRef.portId] for a part port: the path steps joined with `::`. */
    fun portKey(steps: List<String>): PortId = PortId(steps.joinToString("::"))
}
