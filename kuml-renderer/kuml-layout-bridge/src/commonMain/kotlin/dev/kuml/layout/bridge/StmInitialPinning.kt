package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.translated

/** Gap (px) between an initial pseudo-state and the node it points to along the flow axis. */
internal const val INITIAL_TARGET_GAP_PX = 36f

/** Clearance (px) kept free around the moved initial pseudo-state and its connecting edge. */
private const val INITIAL_CLEARANCE_PX = 8f

/** Upper bound of initial pseudo-states placed per diagram (hostile-input cap, the cascade is O(initials x elements)). */
private const val MAX_PINNED_INITIALS = 64

/** Extra room (px) added around the initial when its containing frame has to grow. */
private const val INITIAL_FRAME_MARGIN_PX = 12f

/**
 * The initial pseudo-states of a state-machine [graph] mapped to their target: nodes flagged
 * [dev.kuml.layout.NodeHints.initialState] without incoming edge and with exactly one outgoing edge to another node
 * of the same top-level container (flat in the diagram, or directly inside the state-machine frame). An initial state
 * by definition has no incoming transition (checker rule 101), so this is exact for valid models. Initials inside a
 * compound state or region are left to the engine.
 */
internal fun LayoutGraph.initialTargets(): Map<NodeId, NodeId> {
    val initialIds =
        nodes
            .filter { it.hints.initialState }
            .map { it.id }
            .take(MAX_PINNED_INITIALS)
            .toSet()
    if (initialIds.isEmpty()) return emptyMap()
    val incoming = edges.groupBy { it.target.nodeId }
    val outgoing = edges.groupBy { it.source.nodeId }
    val byId = nodes.associateBy { it.id }
    val topLevel = groups.filter { it.parent == null }.map { it.id }.toSet()
    val out = LinkedHashMap<NodeId, NodeId>()
    for (id in initialIds) {
        val n = byId.getValue(id)
        if (n.groupId != null && n.groupId !in topLevel) continue
        if (incoming[id].orEmpty().isNotEmpty()) continue
        val target =
            outgoing[id]
                .orEmpty()
                .singleOrNull()
                ?.target
                ?.nodeId ?: continue
        val t = byId[target] ?: continue
        if (target != id && t.groupId == n.groupId && !t.hints.initialState) out[id] = target
    }
    return out
}

/** Flow axis of a layout direction: which coordinate runs along the flow and in which sense. */
private class FlowAxis(
    val direction: LayoutDirection,
) {
    val vertical = direction == LayoutDirection.TopToBottom || direction == LayoutDirection.BottomToTop
    private val forward = direction == LayoutDirection.TopToBottom || direction == LayoutDirection.LeftToRight

    /** The flow coordinate of the edge of [r] that faces the start of the flow. */
    fun startOf(r: Rect): Float =
        when (direction) {
            LayoutDirection.TopToBottom -> r.origin.y
            LayoutDirection.BottomToTop -> r.origin.y + r.size.height
            LayoutDirection.LeftToRight -> r.origin.x
            LayoutDirection.RightToLeft -> r.origin.x + r.size.width
        }

    /** The flow coordinate of [p]. */
    fun coord(p: Point): Float = if (vertical) p.y else p.x

    /** True when flow coordinate [a] lies further towards the start of the flow than [b]. */
    fun before(
        a: Float,
        b: Float,
    ): Boolean = if (forward) a < b else a > b

    /** The spot of a node of [size] whose near edge is [gap] away from the flow coordinate [ref], centred on [cx] / [cy]. */
    fun spot(
        ref: Float,
        cx: Float,
        cy: Float,
        size: Size,
    ): Rect {
        val w = size.width
        val h = size.height
        return when (direction) {
            LayoutDirection.TopToBottom -> Rect(origin = Point(x = cx - w / 2f, y = ref - INITIAL_TARGET_GAP_PX - h), size = size)
            LayoutDirection.BottomToTop -> Rect(origin = Point(x = cx - w / 2f, y = ref + INITIAL_TARGET_GAP_PX), size = size)
            LayoutDirection.LeftToRight -> Rect(origin = Point(x = ref - INITIAL_TARGET_GAP_PX - w, y = cy - h / 2f), size = size)
            LayoutDirection.RightToLeft -> Rect(origin = Point(x = ref + INITIAL_TARGET_GAP_PX, y = cy - h / 2f), size = size)
        }
    }

    /** The straight edge of an initial at [spot] to the near edge of [target], centred across the flow. */
    fun edge(
        spot: Rect,
        target: Rect,
    ): EdgeRoute.Direct {
        val cx = target.origin.x + target.size.width / 2f
        val cy = target.origin.y + target.size.height / 2f
        return when (direction) {
            LayoutDirection.TopToBottom ->
                EdgeRoute.Direct(
                    source = Point(x = cx, y = spot.origin.y + spot.size.height),
                    target = Point(x = cx, y = target.origin.y),
                )
            LayoutDirection.BottomToTop ->
                EdgeRoute.Direct(
                    source = Point(x = cx, y = spot.origin.y),
                    target = Point(x = cx, y = target.origin.y + target.size.height),
                )
            LayoutDirection.LeftToRight ->
                EdgeRoute.Direct(
                    source = Point(x = spot.origin.x + spot.size.width, y = cy),
                    target = Point(x = target.origin.x, y = cy),
                )
            LayoutDirection.RightToLeft ->
                EdgeRoute.Direct(
                    source = Point(x = spot.origin.x, y = cy),
                    target = Point(x = target.origin.x + target.size.width, y = cy),
                )
        }
    }
}

/**
 * Places every initial pseudo-state at the start of the flow, in front of its target (state-machine initial
 * placement). The layered engine treats partition hints as ordering hints only and may leave an initial far from its
 * target or beside it (a UML state machine keeps its nodes in a compound group, where the hints are not honoured at
 * all). For every initial, three spots are tried in order, the first that is clear wins:
 *
 * 1. in front of **everything** (all nodes and all edge points): [INITIAL_TARGET_GAP_PX] before the flow start,
 *    centred on the target across the flow, joined by a straight edge;
 * 2. directly in front of the target (the former behaviour);
 * 3. the engine's own position stays.
 *
 * A spot is clear when neither the initial box nor the corridor of its straight edge comes within
 * [INITIAL_CLEARANCE_PX] of another node or of another edge. Edge labels are not known yet at this point (they are
 * placed from the finished routes later), so they are not part of the check. A containing frame (the UML state-machine
 * frame) grows so that it keeps its inset around the moved initial. Notes are placed after the layout and follow the
 * final positions.
 */
internal fun LayoutResult.pinInitialsToTargets(
    graph: LayoutGraph,
    direction: LayoutDirection,
): LayoutResult {
    val initials = graph.initialTargets()
    if (initials.isEmpty()) return this
    val axis = FlowAxis(direction)
    val nodes = nodes.toMutableMap()
    val edges = edges.toMutableMap()
    val groups = groups.toMutableMap()
    val before = extentsOf(this)
    val initialEdgeIds: Map<NodeId, EdgeId> =
        initials.keys.associateWith { i -> graph.edges.first { it.source.nodeId == i }.id }
    val ownEdgeIds = initialEdgeIds.values.toSet()
    var changed = false

    // The flow start is measured on the engine's result before any initial moves, so the result does not depend on the
    // order of the initials; all initials of a diagram end up on one line.
    val flowStart = flowStartOf(nodes = nodes, edges = edges, ownEdgeIds = ownEdgeIds, skip = initials.keys, axis = axis)

    for ((initial, target) in initials) {
        val i = nodes[initial] ?: continue
        val t = nodes[target] ?: continue
        val tb = t.bounds
        val cx = tb.origin.x + tb.size.width / 2f
        val cy = tb.origin.y + tb.size.height / 2f
        val ownEdge = initialEdgeIds.getValue(initial)
        // (spot, strict): the strict check also keeps the edge corridor free of other nodes and edges.
        val candidates =
            buildList {
                if (flowStart != null && axis.before(a = flowStart, b = axis.startOf(tb))) {
                    add(axis.spot(ref = flowStart, cx = cx, cy = cy, size = i.bounds.size) to true)
                }
                add(axis.spot(ref = axis.startOf(tb), cx = cx, cy = cy, size = i.bounds.size) to false)
            }
        val spot =
            candidates
                .firstOrNull { (c, strict) ->
                    isClear(
                        strict = strict,
                        spot = c,
                        route = axis.edge(spot = c, target = tb),
                        initial = initial,
                        target = target,
                        nodes = nodes,
                        edges = edges,
                        ownEdgeIds = ownEdgeIds,
                        graph = graph,
                        groups = groups,
                    )
                }?.first ?: continue
        nodes[initial] =
            NodeLayout(
                bounds = spot,
                ports = emptyMap(),
                labels =
                    i.labels.mapValues {
                        moveRect(r = it.value, dx = spot.origin.x - i.bounds.origin.x, dy = spot.origin.y - i.bounds.origin.y)
                    },
            )
        edges[ownEdge] = axis.edge(spot = spot, target = tb)
        growFramesAround(spot = spot, initial = initial, graph = graph, nodes = nodes, groups = groups, axis = axis)
        changed = true
    }
    if (!changed) return this
    val moved = copy(nodes = nodes, edges = edges, groups = groups)
    // Refit the canvas: keep the margins the engine left around the drawing, so the space the initial occupied before
    // does not stay as an empty band, and nothing is cut off where the initial now sticks out.
    val after = extentsOf(moved)
    val dx = before[0] - after[0]
    val dy = before[1] - after[1]
    val shifted = if (dx != 0f || dy != 0f) moved.translated(dx = dx, dy = dy) else moved
    val width = after[2] + dx + (canvas.width - before[2])
    val height = after[3] + dy + (canvas.height - before[3])
    return shifted.copy(canvas = Size(width = maxOf(width, 1f), height = maxOf(height, 1f))).normalised()
}

/** minX, minY, maxX, maxY over all nodes, groups and edge points of [r]. */
private fun extentsOf(r: LayoutResult): FloatArray {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    fun see(p: Point) {
        minX = minOf(minX, p.x)
        minY = minOf(minY, p.y)
        maxX = maxOf(maxX, p.x)
        maxY = maxOf(maxY, p.y)
    }

    fun see(b: Rect) {
        see(b.origin)
        see(Point(x = b.origin.x + b.size.width, y = b.origin.y + b.size.height))
    }
    r.nodes.values.forEach { see(it.bounds) }
    r.groups.values.forEach { see(it.bounds) }
    r.edges.values.forEach { e -> polyline(e).forEach { see(it) } }
    if (minX == Float.MAX_VALUE) return floatArrayOf(0f, 0f, r.canvas.width, r.canvas.height)
    return floatArrayOf(minX, minY, maxX, maxY)
}

/** The flow coordinate in front of which the initials go: the start of the first node, edge point or edge bend, or null for an empty diagram. */
private fun flowStartOf(
    nodes: Map<NodeId, NodeLayout>,
    edges: Map<EdgeId, EdgeRoute>,
    ownEdgeIds: Set<EdgeId>,
    skip: Set<NodeId>,
    axis: FlowAxis,
): Float? {
    var best: Float? = null

    fun see(v: Float) {
        val b = best
        if (b == null || axis.before(a = v, b = b)) best = v
    }
    for ((id, n) in nodes) if (id !in skip) see(axis.startOf(n.bounds))
    for ((id, r) in edges) if (id !in ownEdgeIds) polyline(r).forEach { see(axis.coord(it)) }
    return best
}

/**
 * Refits the frames around [initial] (its group and the parents of that group) on the start side of the flow: the frame
 * edge goes to the first member (the moved initial included) minus the group padding, so the frame neither keeps the
 * space the initial occupied before nor cuts into it. Across the flow a frame only grows.
 */
private fun growFramesAround(
    spot: Rect,
    initial: NodeId,
    graph: LayoutGraph,
    nodes: Map<NodeId, NodeLayout>,
    groups: MutableMap<GroupId, GroupLayout>,
    axis: FlowAxis,
) {
    var gid = graph.nodes.firstOrNull { it.id == initial }?.groupId
    var guard = 0
    while (gid != null && guard++ < MAX_PINNED_INITIALS) {
        val frame = groups[gid]?.bounds ?: break
        val pad = graph.groups.firstOrNull { it.id == gid }?.padding
        val startPad =
            when (axis.direction) {
                LayoutDirection.TopToBottom -> pad?.top
                LayoutDirection.BottomToTop -> pad?.bottom
                LayoutDirection.LeftToRight -> pad?.left
                LayoutDirection.RightToLeft -> pad?.right
            } ?: INITIAL_FRAME_MARGIN_PX
        val members =
            graph.nodes.filter { it.groupId == gid }.mapNotNull { nodes[it.id]?.bounds } +
                graph.groups.filter { it.parent == gid }.mapNotNull { groups[it.id]?.bounds }
        var first = axis.startOf(spot)
        for (m in members) if (axis.before(a = axis.startOf(m), b = first)) first = axis.startOf(m)
        val wantStart = first - startPad * flowSign(axis)
        val margin = INITIAL_FRAME_MARGIN_PX
        var left = frame.origin.x
        var top = frame.origin.y
        var right = frame.origin.x + frame.size.width
        var bottom = frame.origin.y + frame.size.height
        if (axis.vertical) {
            if (flowSign(axis) > 0) top = wantStart else bottom = wantStart
            left = minOf(left, spot.origin.x - margin)
            right = maxOf(right, spot.origin.x + spot.size.width + margin)
        } else {
            if (flowSign(axis) > 0) left = wantStart else right = wantStart
            top = minOf(top, spot.origin.y - margin)
            bottom = maxOf(bottom, spot.origin.y + spot.size.height + margin)
        }
        groups[gid] =
            GroupLayout(bounds = Rect(origin = Point(x = left, y = top), size = Size(width = right - left, height = bottom - top)))
        gid = graph.groups.firstOrNull { it.id == gid }?.parent
    }
}

private fun flowSign(axis: FlowAxis): Float = if (axis.before(a = 0f, b = 1f)) 1f else -1f

/**
 * True when the initial box at [spot] keeps [INITIAL_CLEARANCE_PX] to every other node and does not cross a group
 * frame border. With [strict] the corridor of the straight [route] must also keep that clearance to every other edge
 * and must not run through another node.
 */
private fun isClear(
    strict: Boolean,
    spot: Rect,
    route: EdgeRoute.Direct,
    initial: NodeId,
    target: NodeId,
    nodes: Map<NodeId, NodeLayout>,
    edges: Map<EdgeId, EdgeRoute>,
    ownEdgeIds: Set<EdgeId>,
    graph: LayoutGraph,
    groups: Map<GroupId, GroupLayout>,
): Boolean {
    val corridor = corridorOf(spot = spot, route = route)
    // Nodes: the box must keep its clearance to all of them; the corridor may touch only the target.
    for ((id, n) in nodes) {
        if (id == initial) continue
        if (n.bounds.overlaps(other = spot, margin = INITIAL_CLEARANCE_PX)) return false
        if (strict && id != target && n.bounds.overlaps(other = corridor, margin = 0f)) return false
    }
    if (strict) {
        for ((id, r) in edges) {
            if (id in ownEdgeIds) continue
            val pts = polyline(r)
            for (k in 0 until pts.size - 1) {
                if (segmentHitsRect(a = pts[k], b = pts[k + 1], rect = corridor, margin = INITIAL_CLEARANCE_PX)) return false
            }
        }
    }
    // The frames that hold the initial grow around it afterwards; every other frame must not be crossed.
    val own = ownFrames(graph = graph, initial = initial)
    return graph.groups.none { g -> g.id !in own && groups[g.id]?.bounds?.let { gb -> spot.overlapsBorderOf(gb) } == true }
}

/** The group of [initial] and all of its parent groups. */
private fun ownFrames(
    graph: LayoutGraph,
    initial: NodeId,
): Set<GroupId> {
    val out = LinkedHashSet<GroupId>()
    var gid = graph.nodes.firstOrNull { it.id == initial }?.groupId
    while (gid != null && out.add(gid)) gid = graph.groups.firstOrNull { it.id == gid }?.parent
    return out
}

/** The rectangle spanned by the initial box and the end of its straight edge, as wide as the box across the flow. */
private fun corridorOf(
    spot: Rect,
    route: EdgeRoute.Direct,
): Rect {
    val x0 = minOf(spot.origin.x, route.target.x)
    val y0 = minOf(spot.origin.y, route.target.y)
    val x1 = maxOf(spot.origin.x + spot.size.width, route.target.x)
    val y1 = maxOf(spot.origin.y + spot.size.height, route.target.y)
    return Rect(origin = Point(x = x0, y = y0), size = Size(width = x1 - x0, height = y1 - y0))
}

/** Every route as a polyline: end points, bends, and the control points of a spline (a coarse but safe hull). */
private fun polyline(r: EdgeRoute): List<Point> =
    when (r) {
        is EdgeRoute.Direct -> listOf(r.source, r.target)
        is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + r.target
        is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + r.target
        is EdgeRoute.Bezier -> listOf(r.source) + r.controlPoints + r.target
    }

/** Segment [a]-[b] against [rect] grown by [margin] (Liang-Barsky clipping). */
private fun segmentHitsRect(
    a: Point,
    b: Point,
    rect: Rect,
    margin: Float,
): Boolean {
    val xMin = rect.origin.x - margin
    val xMax = rect.origin.x + rect.size.width + margin
    val yMin = rect.origin.y - margin
    val yMax = rect.origin.y + rect.size.height + margin
    val dx = b.x - a.x
    val dy = b.y - a.y
    var t0 = 0f
    var t1 = 1f
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - xMin, xMax - a.x, a.y - yMin, yMax - a.y)
    for (k in 0 until 4) {
        if (p[k] == 0f) {
            if (q[k] < 0f) return false
        } else {
            val t = q[k] / p[k]
            if (p[k] < 0f) {
                if (t > t1) return false
                if (t > t0) t0 = t
            } else {
                if (t < t0) return false
                if (t < t1) t1 = t
            }
        }
    }
    return true
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
    val disjoint = !overlaps(other = frame, margin = 0f)
    return !inside && !disjoint
}
