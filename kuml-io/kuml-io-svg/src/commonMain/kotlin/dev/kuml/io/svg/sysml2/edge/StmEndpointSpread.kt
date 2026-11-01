package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Spreads the ends of orthogonal transitions that meet the same side of a state (PAL wave 2D-a): ends on one side of
 * one node keep at least [MIN_GAP_PX] between each other, so neighbouring arrow heads and the labels beside them do
 * not run into each other. The group is centred around its original position and stays [SIDE_MARGIN_PX] inside the side.
 * The route stays orthogonal: the previous way point moves along with the end, or the last segment gets a small jog.
 * Pseudo-states are not handled here (see [StmPseudostateEndpoints]). Deterministic: groups and members are processed in
 * sorted order.
 */
internal object StmEndpointSpread {
    const val MIN_GAP_PX = 16f
    private const val SIDE_MARGIN_PX = 8f
    private const val TOL = 1.5f
    private const val MIN_SEGMENT_PX = 6f
    private const val JOG_RADIUS_PX = 8f

    private enum class Side { Top, Bottom, Left, Right }

    private data class End(
        val edgeId: String,
        val atSource: Boolean,
        val nodeId: String,
        val side: Side,
        val coord: Float,
    )

    /** [ends] lists (edge id, source id, target id); [boxes] are the shifted bounds by node id (pseudo-states left out). */
    fun spread(
        routes: Map<String, EdgeRoute>,
        ends: List<Triple<String, String, String>>,
        boxes: Map<String, Rect>,
    ): Map<String, EdgeRoute> {
        val out = LinkedHashMap(routes)
        val found = ArrayList<End>()
        for ((edgeId, src, tgt) in ends.sortedBy { it.first }) {
            val route = out[edgeId] ?: continue
            if (route is EdgeRoute.Bezier) continue
            for ((nodeId, atSource) in listOf(tgt to false, src to true)) {
                val box = boxes[nodeId] ?: continue
                detect(route = route, atSource = atSource, box = box)?.let { (side, coord) ->
                    found += End(edgeId = edgeId, atSource = atSource, nodeId = nodeId, side = side, coord = coord)
                }
            }
        }
        val groups = found.groupBy { it.nodeId to it.side }
        for ((key, members) in groups.entries.sortedWith(compareBy({ it.key.first }, { it.key.second.ordinal }))) {
            if (members.size < 2) continue
            val box = boxes.getValue(key.first)
            val sorted = members.sortedWith(compareBy({ it.coord }, { it.edgeId }, { it.atSource }))
            val (lo, hi) =
                when (key.second) {
                    Side.Top, Side.Bottom -> box.origin.x to box.origin.x + box.size.width
                    Side.Left, Side.Right -> box.origin.y to box.origin.y + box.size.height
                }
            val coords = spreadCoords(orig = sorted.map { it.coord }, lo = lo, hi = hi)
            sorted.forEachIndexed { i, m ->
                val delta = coords[i] - m.coord
                if (abs(delta) < 0.01f) return@forEachIndexed
                val cur = out[m.edgeId] ?: return@forEachIndexed
                out[m.edgeId] = shiftEnd(route = cur, atSource = m.atSource, side = m.side, delta = delta)
            }
        }
        return out
    }

    private fun spreadCoords(
        orig: List<Float>,
        lo: Float,
        hi: Float,
    ): List<Float> {
        val n = orig.size
        val avail = hi - lo - 2 * SIDE_MARGIN_PX
        if (avail <= 0f) return orig
        val gap = min(MIN_GAP_PX, avail / (n - 1))
        val pushed = FloatArray(n)
        pushed[0] = orig[0]
        for (i in 1 until n) pushed[i] = max(orig[i], pushed[i - 1] + gap)
        if (pushed.indices.all { abs(pushed[it] - orig[it]) < 0.01f }) return orig
        var adj = (orig.average() - pushed.average()).toFloat()
        val first = pushed[0] + adj
        if (first < lo + SIDE_MARGIN_PX) adj += (lo + SIDE_MARGIN_PX) - first
        val last = pushed[n - 1] + adj
        if (last > hi - SIDE_MARGIN_PX) adj -= last - (hi - SIDE_MARGIN_PX)
        return pushed.map { it + adj }
    }

    private fun points(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }

    private fun rebuild(
        route: EdgeRoute,
        pts: List<Point>,
    ): EdgeRoute {
        val way = pts.subList(1, pts.size - 1).toList()
        return when (route) {
            is EdgeRoute.Direct ->
                if (way.isEmpty()) {
                    route.copy(source = pts.first(), target = pts.last())
                } else {
                    EdgeRoute.OrthogonalRounded(source = pts.first(), target = pts.last(), waypoints = way, cornerRadiusPx = JOG_RADIUS_PX)
                }
            is EdgeRoute.OrthogonalRounded -> route.copy(source = pts.first(), target = pts.last(), waypoints = way)
            is EdgeRoute.TreeRounded -> route.copy(source = pts.first(), target = pts.last(), waypoints = way)
            is EdgeRoute.Bezier -> route
        }
    }

    private fun detect(
        route: EdgeRoute,
        atSource: Boolean,
        box: Rect,
    ): Pair<Side, Float>? {
        val pts = points(route)
        if (pts.size < 2) return null
        val e = if (atSource) pts[0] else pts[pts.size - 1]
        val p = if (atSource) pts[1] else pts[pts.size - 2]
        val left = box.origin.x
        val right = box.origin.x + box.size.width
        val top = box.origin.y
        val bottom = box.origin.y + box.size.height
        val vertical = abs(p.x - e.x) <= 0.5f && abs(p.y - e.y) > 0.5f
        val horizontal = abs(p.y - e.y) <= 0.5f && abs(p.x - e.x) > 0.5f
        return when {
            vertical && e.x in (left - TOL)..(right + TOL) ->
                when {
                    abs(e.y - top) <= TOL -> Side.Top to e.x
                    abs(e.y - bottom) <= TOL -> Side.Bottom to e.x
                    else -> null
                }
            horizontal && e.y in (top - TOL)..(bottom + TOL) ->
                when {
                    abs(e.x - left) <= TOL -> Side.Left to e.y
                    abs(e.x - right) <= TOL -> Side.Right to e.y
                    else -> null
                }
            else -> null
        }
    }

    private fun shiftEnd(
        route: EdgeRoute,
        atSource: Boolean,
        side: Side,
        delta: Float,
    ): EdgeRoute {
        val pts = points(route).toMutableList()
        val vertical = side == Side.Top || side == Side.Bottom
        val ei = if (atSource) 0 else pts.size - 1
        val pi = if (atSource) 1 else pts.size - 2
        val e = pts[ei]
        val p = pts[pi]

        fun moved(pt: Point) = if (vertical) pt.copy(x = pt.x + delta) else pt.copy(y = pt.y + delta)
        // The point beyond the way point P: P may move along only if the segment into it runs across the end segment.
        val qi = if (atSource) 2 else pts.size - 3
        val canMoveP =
            pi in 1 until pts.size - 1 &&
                qi in pts.indices &&
                run {
                    val q = pts[qi]
                    val across = if (vertical) abs(q.y - p.y) <= 0.5f else abs(q.x - p.x) <= 0.5f
                    val newLen = if (vertical) abs(moved(p).x - q.x) else abs(moved(p).y - q.y)
                    val sameSide = if (vertical) (moved(p).x - q.x) * (p.x - q.x) > 0f else (moved(p).y - q.y) * (p.y - q.y) > 0f
                    across && sameSide && newLen >= MIN_SEGMENT_PX
                }
        if (canMoveP) {
            pts[ei] = moved(e)
            pts[pi] = moved(p)
            return rebuild(route = route, pts = pts)
        }
        // Jog: P -> (P.x, mid) -> (E'.x, mid) -> E'.
        val span = if (vertical) abs(p.y - e.y) else abs(p.x - e.x)
        if (span < 2 * MIN_SEGMENT_PX) return route
        val mid = if (vertical) (p.y + e.y) / 2f else (p.x + e.x) / 2f
        val j1 = if (vertical) Point(x = p.x, y = mid) else Point(x = mid, y = p.y)
        val j2 = if (vertical) Point(x = e.x + delta, y = mid) else Point(x = mid, y = e.y + delta)
        val e2 = moved(e)
        val rebuilt =
            if (atSource) {
                listOf(e2, j2, j1) + pts.drop(1)
            } else {
                pts.dropLast(1) + listOf(j1, j2, e2)
            }
        return rebuild(route = route, pts = rebuilt)
    }
}
