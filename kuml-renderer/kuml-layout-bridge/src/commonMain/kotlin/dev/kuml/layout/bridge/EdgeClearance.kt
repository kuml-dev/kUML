@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Target distance (px) between two parallel edges (PAL wave I). */
internal const val EDGE_EDGE_TARGET_GAP_PX = 16f

/** Smallest accepted distance (px) between two parallel edges that share a corridor. */
internal const val EDGE_EDGE_MIN_GAP_PX = 12f

/** Smallest accepted distance (px) between an edge segment and a node that is neither its source nor its target. */
internal const val EDGE_NODE_MIN_GAP_PX = 12f

/** Air (px) kept between a label box and a foreign edge segment. */
internal const val LABEL_CLEARANCE_PX = 4f

/** Offset (px) of a label from its own edge. */
internal const val LABEL_OWN_EDGE_OFFSET_PX = 4f

/** Minimum distance (px) between an initial-transition label and the arrowhead. */
internal const val INIT_LABEL_ARROWHEAD_GAP_PX = 16f

/** Upper bound of routes / segments examined by the geometry helpers (hostile-input cap). */
private const val MAX_ROUTES = 512
private const val MAX_POINTS_PER_ROUTE = 64

/**
 * Smallest distance between two parallel (axis-aligned) segments, or `null` when they are not parallel or their
 * projections on the common axis do not overlap.
 */
internal fun parallelGap(
    a: Pair<Point, Point>,
    b: Pair<Point, Point>,
): Float? {
    val aVertical = abs(a.first.x - a.second.x) < PARALLEL_EPS
    val aHorizontal = abs(a.first.y - a.second.y) < PARALLEL_EPS
    val bVertical = abs(b.first.x - b.second.x) < PARALLEL_EPS
    val bHorizontal = abs(b.first.y - b.second.y) < PARALLEL_EPS
    return when {
        aVertical && bVertical ->
            if (overlap(a0 = a.first.y, a1 = a.second.y, b0 = b.first.y, b1 = b.second.y)) abs(a.first.x - b.first.x) else null
        aHorizontal && bHorizontal ->
            if (overlap(a0 = a.first.x, a1 = a.second.x, b0 = b.first.x, b1 = b.second.x)) abs(a.first.y - b.first.y) else null
        else -> null
    }
}

private const val PARALLEL_EPS = 0.5f

private fun overlap(
    a0: Float,
    a1: Float,
    b0: Float,
    b1: Float,
): Boolean = min(max(a0, a1), max(b0, b1)) - max(min(a0, a1), min(b0, b1)) > PARALLEL_EPS

/**
 * Number of proper crossings between the polylines in [routes] (two different routes only; touching at a shared end
 * point and collinear overlap do not count). O(segments^2), capped.
 */
internal fun countCrossings(routes: Collection<List<Point>>): Int {
    val capped = routes.take(MAX_ROUTES).map { it.take(MAX_POINTS_PER_ROUTE) }
    var count = 0
    for (i in capped.indices) {
        for (j in i + 1 until capped.size) {
            for ((a, b) in capped[i].zipWithNext()) {
                for ((c, d) in capped[j].zipWithNext()) {
                    if (properlyCross(a = a, b = b, c = c, d = d)) count++
                }
            }
        }
    }
    return count
}

private fun orient(
    p: Point,
    q: Point,
    r: Point,
): Float = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)

private fun properlyCross(
    a: Point,
    b: Point,
    c: Point,
    d: Point,
): Boolean {
    val o1 = orient(p = a, q = b, r = c)
    val o2 = orient(p = a, q = b, r = d)
    val o3 = orient(p = c, q = d, r = a)
    val o4 = orient(p = c, q = d, r = b)
    return o1 * o2 < -CROSS_EPS && o3 * o4 < -CROSS_EPS
}

private const val CROSS_EPS = 0.01f

/** Shortest overlap (px) of the projections of two axis-parallel segments on their common axis; 0 when they are not parallel. */
internal fun parallelOverlap(
    a: Pair<Point, Point>,
    b: Pair<Point, Point>,
): Float {
    val aVertical = abs(a.first.x - a.second.x) < PARALLEL_EPS
    val aHorizontal = abs(a.first.y - a.second.y) < PARALLEL_EPS
    val bVertical = abs(b.first.x - b.second.x) < PARALLEL_EPS
    val bHorizontal = abs(b.first.y - b.second.y) < PARALLEL_EPS
    val (a0, a1, b0, b1) =
        when {
            aVertical && bVertical -> listOf(a.first.y, a.second.y, b.first.y, b.second.y)
            aHorizontal && bHorizontal -> listOf(a.first.x, a.second.x, b.first.x, b.second.x)
            else -> return 0f
        }
    return (min(max(a0, a1), max(b0, b1)) - max(min(a0, a1), min(b0, b1))).coerceAtLeast(0f)
}

/** Smallest projection overlap (px) for two parallel segments to count as running side by side. */
internal const val PARALLEL_MIN_OVERLAP_PX = 8f

/** A node side shorter than this cannot hold two edge attachments 16 px apart with a margin at both corners. */
internal const val MIN_NODE_EXTENT_FOR_TWO_LANES_PX = EDGE_EDGE_TARGET_GAP_PX + 7f

/** Length (px) at both ends of a route that belongs to its own attachment and is exempt from the node-gap rule. */
internal const val ATTACH_EXEMPT_PX = 12f

/** `route` without its first and last [exemptPx] of path length; empty when the route is shorter than twice that. */
internal fun trimEnds(
    route: List<Point>,
    exemptPx: Float,
): List<Point> {
    val pts = route.take(MAX_POINTS_PER_ROUTE)
    if (pts.size < 2 || exemptPx <= 0f) return pts

    fun cut(list: List<Point>): List<Point> {
        var left = exemptPx
        var i = 0
        while (i < list.size - 1) {
            val len = dist(list[i], list[i + 1])
            if (len > left) {
                val t = left / len
                val p = Point(x = list[i].x + (list[i + 1].x - list[i].x) * t, y = list[i].y + (list[i + 1].y - list[i].y) * t)
                return listOf(p) + list.drop(i + 1)
            }
            left -= len
            i++
        }
        return emptyList()
    }
    val front = cut(pts)
    if (front.size < 2) return emptyList()
    val back = cut(front.reversed())
    return if (back.size < 2) emptyList() else back.reversed()
}

private fun dist(
    a: Point,
    b: Point,
): Float = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

private fun segmentPointDistance(
    a: Point,
    b: Point,
    p: Point,
): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return dist(p, Point(x = a.x + dx * t, y = a.y + dy * t))
}

private fun segmentsDistance(
    a: Point,
    b: Point,
    c: Point,
    d: Point,
): Float =
    if (properlyCross(a = a, b = b, c = c, d = d)) {
        0f
    } else {
        minOf(segmentPointDistance(a, b, c), segmentPointDistance(a, b, d), segmentPointDistance(c, d, a), segmentPointDistance(c, d, b))
    }

/**
 * Distance (px) between [route] (without its first and last [exemptNearEndpointPx]) and the outline of [rect]; `0`
 * when the route touches or enters the rectangle, `null` when nothing is left of the route after trimming.
 */
internal fun edgeNodeGap(
    route: List<Point>,
    rect: Rect,
    exemptNearEndpointPx: Float = ATTACH_EXEMPT_PX,
): Float? {
    val pts = trimEnds(route = route, exemptPx = exemptNearEndpointPx)
    if (pts.size < 2) return null
    val x0 = rect.origin.x
    val y0 = rect.origin.y
    val x1 = x0 + rect.size.width
    val y1 = y0 + rect.size.height
    val corners = listOf(Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1))
    var best = Float.MAX_VALUE
    for ((a, b) in pts.zipWithNext()) {
        if (segmentIntersectsRect(a = a, b = b, r = rect)) return 0f
        for (i in 0 until 4) best = minOf(best, segmentsDistance(a, b, corners[i], corners[(i + 1) % 4]))
    }
    return best
}

/** Two parallel segments of different edges that run closer than [EDGE_EDGE_TARGET_GAP_PX]. */
internal data class ParallelViolation(
    val first: EdgeId,
    val second: EdgeId,
    val gap: Float,
)

/** A route that comes closer than [EDGE_NODE_MIN_GAP_PX] to a node or frame it neither starts nor ends at. */
internal data class EdgeNodeViolation(
    val edge: EdgeId,
    val obstacle: String,
    val gap: Float,
)

/** All clearance violations of one layout (PAL wave J). */
internal data class ClearanceViolations(
    val parallel: List<ParallelViolation>,
    val edgeNode: List<EdgeNodeViolation>,
) {
    val count: Int get() = parallel.size + edgeNode.size
}

/**
 * Finds edges that run too close to each other or to foreign nodes, composite frames (seen from inside) and header
 * bands. Knows no node names; uses only geometry and the graph's endpoints. Hostile-input caps apply.
 */
internal fun detectViolations(
    result: LayoutResult,
    graph: LayoutGraph,
    portAware: Boolean = false,
): ClearanceViolations {
    val routes = LinkedHashMap<EdgeId, List<Point>>()
    for (e in graph.edges.take(MAX_ROUTES)) {
        val pts = result.edges[e.id]?.let { polylinePoints(it) } ?: continue
        routes[e.id] = pts.take(MAX_POINTS_PER_ROUTE)
    }
    val edgeById = graph.edges.associateBy { it.id }
    val nodeRects = result.nodes.mapValues { it.value.bounds }

    val parallel = ArrayList<ParallelViolation>()
    val ids = routes.keys.toList()
    for (i in ids.indices) {
        for (j in i + 1 until ids.size) {
            val ei = edgeById.getValue(ids[i])
            val ej = edgeById.getValue(ids[j])
            val shared = setOf(ei.source.nodeId, ei.target.nodeId).intersect(setOf(ej.source.nodeId, ej.target.nodeId))
            // portAware (IBD, PAL wave T): only a shared port end is a bundle (Kare's fan-out stem); two ports of one part are not.
            val shareNode = if (portAware) sharesPortEnd(ei, ej) else shared.isNotEmpty()
            if (portAware && shareNode) continue
            var worst: Float? = null
            for (sa in routes.getValue(ids[i]).zipWithNext()) {
                for (sb in routes.getValue(ids[j]).zipWithNext()) {
                    val gap = parallelGap(sa, sb) ?: continue
                    if (parallelOverlap(sa, sb) < PARALLEL_MIN_OVERLAP_PX) continue
                    if (gap >= EDGE_EDGE_TARGET_GAP_PX) continue
                    // Bundled stubs of edges that leave or enter the same node run on one line on purpose.
                    if (shareNode && gap < PARALLEL_EPS) continue
                    // A pseudo node too small to hold two lanes (16 px apart, corner margins kept) cannot be fixed by moving edges.
                    val vertical = abs(sa.first.x - sa.second.x) < PARALLEL_EPS
                    if (shared.any { n ->
                            nodeRects[n]?.let {
                                (if (vertical) it.size.width else it.size.height) <
                                    MIN_NODE_EXTENT_FOR_TWO_LANES_PX
                            } ==
                                true
                        }
                    ) {
                        continue
                    }
                    if (nodeBetween(sa = sa, sb = sb, nodes = nodeRects.values)) continue
                    if (worst == null || gap < worst) worst = gap
                }
            }
            if (worst != null) parallel += ParallelViolation(first = ids[i], second = ids[j], gap = worst)
        }
    }

    val edgeNode = ArrayList<EdgeNodeViolation>()
    for ((id, pts) in routes) {
        val e = edgeById.getValue(id)
        val own = setOf(e.source.nodeId, e.target.nodeId)
        for ((nid, rect) in nodeRects) {
            if (nid in own) continue
            val gap = edgeNodeGap(route = pts, rect = rect) ?: continue
            if (gap < EDGE_NODE_MIN_GAP_PX) edgeNode += EdgeNodeViolation(edge = id, obstacle = nid.value, gap = gap)
        }
        val sourceBox = nodeRects[e.source.nodeId]
        val targetBox = nodeRects[e.target.nodeId]
        for (g in graph.groups) {
            val frame = result.groups[g.id]?.bounds ?: continue
            val inside = sourceBox != null && targetBox != null && containsRect(frame, sourceBox) && containsRect(frame, targetBox)
            if (inside) {
                frameInnerGap(pts = pts, frame = frame)?.let { gap ->
                    if (gap < EDGE_NODE_MIN_GAP_PX) edgeNode += EdgeNodeViolation(edge = id, obstacle = "frame:${g.id.value}", gap = gap)
                }
            }
            if (g.headerTextWidths != null &&
                (sourceBox == null || !containsRect(frame, sourceBox) || (targetBox != null && containsRect(frame, targetBox)))
            ) {
                val header = headerRect(group = g, frame = frame)
                val gap = edgeNodeGap(route = pts, rect = header, exemptNearEndpointPx = 0f)
                if (gap != null && gap < EDGE_NODE_MIN_GAP_PX && inside) {
                    edgeNode += EdgeNodeViolation(edge = id, obstacle = "header:${g.id.value}", gap = gap)
                }
            }
        }
    }
    return ClearanceViolations(parallel = parallel, edgeNode = edgeNode)
}

/** True when [a] and [b] end at the same port of the same node (same non-null [dev.kuml.layout.EndpointRef.portId]). */
private fun sharesPortEnd(
    a: dev.kuml.layout.LayoutEdge,
    b: dev.kuml.layout.LayoutEdge,
): Boolean =
    listOf(a.source, a.target).any { x ->
        x.portId != null && listOf(b.source, b.target).any { y -> y.nodeId == x.nodeId && y.portId == x.portId }
    }

/**
 * Number of overlapping parallel segment pairs (overlap at least [PARALLEL_MIN_OVERLAP_PX], gap below
 * [EDGE_EDGE_TARGET_GAP_PX]) of different edges, with the same exemptions as [detectViolations]; the IBD lane fix counts
 * with it. PAL wave T.
 */
internal fun parallelOverlapCount(
    result: LayoutResult,
    graph: LayoutGraph,
    portAware: Boolean,
): Int = detectViolations(result = result, graph = graph, portAware = portAware).parallel.size

/** True when a gate should run a second layout: the first result has at least one clearance violation. */
internal fun needsRelayout(
    result: LayoutResult,
    graph: LayoutGraph,
): Boolean = detectViolations(result = result, graph = graph).count > 0

private fun containsRect(
    frame: Rect,
    inner: Rect,
): Boolean =
    inner.origin.x >= frame.origin.x &&
        inner.origin.y >= frame.origin.y &&
        inner.origin.x + inner.size.width <= frame.origin.x + frame.size.width &&
        inner.origin.y + inner.size.height <= frame.origin.y + frame.size.height

/** Smallest distance (px) of the trimmed route to the inner side of [frame]; `null` when the route has no free part. */
private fun frameInnerGap(
    pts: List<Point>,
    frame: Rect,
): Float? {
    val trimmed = trimEnds(route = pts, exemptPx = ATTACH_EXEMPT_PX)
    if (trimmed.isEmpty()) return null
    var best = Float.MAX_VALUE
    for (p in trimmed) {
        best =
            minOf(
                best,
                p.x - frame.origin.x,
                frame.origin.x + frame.size.width - p.x,
                p.y - frame.origin.y,
                frame.origin.y + frame.size.height - p.y,
            )
    }
    return best
}

private fun nodeBetween(
    sa: Pair<Point, Point>,
    sb: Pair<Point, Point>,
    nodes: Collection<Rect>,
): Boolean {
    val strip =
        Rect(
            origin =
                Point(
                    x = minOf(sa.first.x, sa.second.x, sb.first.x, sb.second.x),
                    y = minOf(sa.first.y, sa.second.y, sb.first.y, sb.second.y),
                ),
            size =
                dev.kuml.layout.Size(
                    width =
                        maxOf(sa.first.x, sa.second.x, sb.first.x, sb.second.x) - minOf(sa.first.x, sa.second.x, sb.first.x, sb.second.x),
                    height =
                        maxOf(sa.first.y, sa.second.y, sb.first.y, sb.second.y) - minOf(sa.first.y, sa.second.y, sb.first.y, sb.second.y),
                ),
        )
    return nodes.any { n ->
        n.origin.x < strip.origin.x + strip.size.width &&
            n.origin.x + n.size.width > strip.origin.x &&
            n.origin.y < strip.origin.y + strip.size.height &&
            n.origin.y + n.size.height > strip.origin.y
    }
}
