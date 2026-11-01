package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect

/** Gap (px) between an initial pseudo-state and its target along the flow axis. */
internal const val INITIAL_TARGET_GAP_PX = 36f

/** Clearance (px) kept free around the moved initial pseudo-state. */
private const val INITIAL_CLEARANCE_PX = 8f

/**
 * The initial pseudo-states of a state-machine [graph] (PAL wave 2D-b): flat nodes of pseudo-state size without
 * incoming edge and with exactly one outgoing edge to another flat node. An initial state by definition has no
 * incoming transition (checker rule 101), so this is exact for valid models.
 */
internal fun LayoutGraph.initialPseudoStates(pseudoSize: Float): Map<NodeId, NodeId> {
    val incoming = edges.groupBy { it.target.nodeId }
    val outgoing = edges.groupBy { it.source.nodeId }
    val flat = nodes.filter { it.groupId == null }.associateBy { it.id }
    val out = LinkedHashMap<NodeId, NodeId>()
    for (n in flat.values) {
        if (n.intrinsicSize.width != pseudoSize || n.intrinsicSize.height != pseudoSize) continue
        if (incoming[n.id].orEmpty().isNotEmpty()) continue
        val target =
            outgoing[n.id]
                .orEmpty()
                .singleOrNull()
                ?.target
                ?.nodeId ?: continue
        if (target != n.id && target in flat) out[n.id] = target
    }
    return out
}

/**
 * Moves every initial pseudo-state directly in front of its target (PAL wave 2D-b): centred on the target's centre
 * across the flow, [INITIAL_TARGET_GAP_PX] away along it, joined by a straight two-point edge. The layered engine
 * treats the partition (layer) hints as ordering hints only and may leave a source node far from its target, which
 * made the initial transition a long arc. A pseudo-state whose new spot would touch another node stays where the
 * engine put it. Only flat nodes are moved, compound members keep the engine's placement.
 */
internal fun LayoutResult.pinInitialsToTargets(
    graph: LayoutGraph,
    direction: LayoutDirection,
    pseudoSize: Float,
): LayoutResult {
    val initials = graph.initialPseudoStates(pseudoSize)
    if (initials.isEmpty()) return this
    val nodes = nodes.toMutableMap()
    val edges = edges.toMutableMap()
    var changed = false
    for ((initial, target) in initials) {
        val i = nodes[initial] ?: continue
        val t = nodes[target] ?: continue
        val w = i.bounds.size.width
        val h = i.bounds.size.height
        val tb = t.bounds
        val cx = tb.origin.x + tb.size.width / 2f
        val cy = tb.origin.y + tb.size.height / 2f
        val spot =
            when (direction) {
                LayoutDirection.TopToBottom ->
                    Rect(
                        origin = Point(x = cx - w / 2f, y = tb.origin.y - INITIAL_TARGET_GAP_PX - h),
                        size = i.bounds.size,
                    )
                LayoutDirection.BottomToTop ->
                    Rect(
                        origin = Point(x = cx - w / 2f, y = tb.origin.y + tb.size.height + INITIAL_TARGET_GAP_PX),
                        size = i.bounds.size,
                    )
                LayoutDirection.LeftToRight ->
                    Rect(
                        origin = Point(x = tb.origin.x - INITIAL_TARGET_GAP_PX - w, y = cy - h / 2f),
                        size = i.bounds.size,
                    )
                LayoutDirection.RightToLeft ->
                    Rect(
                        origin = Point(x = tb.origin.x + tb.size.width + INITIAL_TARGET_GAP_PX, y = cy - h / 2f),
                        size = i.bounds.size,
                    )
            }
        val clear =
            nodes.none { (id, n) -> id != initial && n.bounds.overlaps(other = spot, margin = INITIAL_CLEARANCE_PX) } &&
                graph.groups.none { g -> this.groups[g.id]?.bounds?.let { gb -> spot.overlapsBorderOf(gb) } == true }
        if (!clear) continue
        val edge = graph.edges.single { it.source.nodeId == initial }
        val source: Point
        val tgt: Point
        when (direction) {
            LayoutDirection.TopToBottom -> {
                source = Point(x = cx, y = spot.origin.y + h)
                tgt = Point(x = cx, y = tb.origin.y)
            }
            LayoutDirection.BottomToTop -> {
                source = Point(x = cx, y = spot.origin.y)
                tgt = Point(x = cx, y = tb.origin.y + tb.size.height)
            }
            LayoutDirection.LeftToRight -> {
                source = Point(x = spot.origin.x + w, y = cy)
                tgt = Point(x = tb.origin.x, y = cy)
            }
            LayoutDirection.RightToLeft -> {
                source = Point(x = spot.origin.x, y = cy)
                tgt = Point(x = tb.origin.x + tb.size.width, y = cy)
            }
        }
        nodes[initial] =
            NodeLayout(
                bounds = spot,
                ports = emptyMap(),
                labels =
                    i.labels.mapValues {
                        moveRect(
                            r = it.value,
                            dx = spot.origin.x - i.bounds.origin.x,
                            dy = spot.origin.y - i.bounds.origin.y,
                        )
                    },
            )
        edges[edge.id] = EdgeRoute.Direct(source = source, target = tgt)
        changed = true
    }
    return if (changed) copy(nodes = nodes, edges = edges).normalised() else this
}

private fun moveRect(
    r: Rect,
    dx: Float,
    dy: Float,
): Rect = Rect(origin = Point(x = r.origin.x + dx, y = r.origin.y + dy), size = r.size)

private fun Rect.overlaps(
    other: Rect,
    margin: Float,
): Boolean =
    origin.x - margin < other.origin.x + other.size.width &&
        origin.x + size.width + margin > other.origin.x &&
        origin.y - margin < other.origin.y + other.size.height &&
        origin.y + size.height + margin > other.origin.y

/** True when [this] crosses the border line of the group frame [frame] (partly inside, partly outside). */
private fun Rect.overlapsBorderOf(frame: Rect): Boolean {
    val inside =
        origin.x >= frame.origin.x &&
            origin.y >= frame.origin.y &&
            origin.x + size.width <= frame.origin.x + frame.size.width &&
            origin.y + size.height <= frame.origin.y + frame.size.height
    val disjoint =
        !overlaps(other = frame, margin = 0f)
    return !inside && !disjoint
}
