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

/** Neighbouring segments further out than this from a frame side keep their place (PAL wave P). */
private const val STACK_REACH_PX = 3 * FRAME_STANDOFF_TARGET_PX

/** At most this many segments per frame side are ordered by trying all permutations (5! = 120). */
private const val MAX_PERMUTED = 5

/** At most this many segments per frame side are moved at all (hostile-input cap). */
private const val MAX_STACK = 12

private const val MAX_GROUPS_CAP = 256
private const val MAX_EDGES_CAP = 512
private const val MAX_POINTS_CAP = 64
private const val EPS = 0.5f

/** Lateral margin (px) within which two stacked segments are treated as sharing a corridor. */
private const val LANE_CONFLICT_MARGIN_PX = 4f

private enum class Side { TOP, BOTTOM, LEFT, RIGHT }

private class Seg(
    val edge: EdgeId,
    val index: Int,
    val offset: Float,
    val lo: Float,
    val hi: Float,
)

/**
 * PAL wave P: edges that run along a composite frame closer than [FRAME_STANDOFF_MIN_PX] (defect class F1) are moved
 * onto lanes [FRAME_STANDOFF_TARGET_PX], 2x and 3x that far outside the frame side. Only interior segments of
 * orthogonal routes move (with their two bends), so every route stays orthogonal and keeps its end points. A move is
 * kept only when it adds no edge crossing, no new edge/node violation and stays on the canvas; the whole pass is kept
 * only when no measured component (clearance violations, F1, F2) gets worse and one gets better. A layout without a
 * frame violation is returned as the same instance.
 */
internal object StmFrameStandoff {
    fun apply(
        result: LayoutResult,
        graph: LayoutGraph,
    ): LayoutResult {
        if (detectFrameViolations(result = result, graph = graph).f1 == 0) return result
        var current = result
        for (g in graph.groups.take(MAX_GROUPS_CAP)) {
            for (side in Side.values()) current = standoffSide(result = current, graph = graph, groupId = g.id.value, side = side)
        }
        if (current === result) return result
        val spread = current.spreadParallelEdges(graph = graph)
        val base = vector(result = result, graph = graph)
        var best: LayoutResult = result
        var bestVec = base
        for (candidate in listOf(current, spread)) {
            val v = vector(result = candidate, graph = graph)
            if (v.zip(base).all { (a, b) -> a <= b } && v.zip(bestVec).any { (a, b) -> a < b } && v.sum() <= bestVec.sum()) {
                best = candidate
                bestVec = v
            }
        }
        return best
    }

    private fun vector(
        result: LayoutResult,
        graph: LayoutGraph,
    ): List<Int> {
        val j = detectViolations(result = result, graph = graph)
        val f = detectFrameViolations(result = result, graph = graph)
        return listOf(j.count, f.f1, f.f2)
    }

    private fun allRoutes(result: LayoutResult): List<List<Point>> = result.edges.values.mapNotNull { polylinePoints(it) }

    private fun standoffSide(
        result: LayoutResult,
        graph: LayoutGraph,
        groupId: String,
        side: Side,
    ): LayoutResult {
        val group = graph.groups.first { it.id.value == groupId }
        val frame = result.groups[group.id]?.bounds ?: return result
        val horizontalSide = side == Side.TOP || side == Side.BOTTOM
        val segs = ArrayList<Seg>()
        var violator = false
        val (sLo, sHi) = span(frame = frame, side = side)
        for (e in graph.edges.take(MAX_EDGES_CAP)) {
            val pts = result.edges[e.id]?.let { polylinePoints(it) } ?: continue
            if (pts.size > MAX_POINTS_CAP || pts.size < 4) continue
            for (i in 1 until pts.size - 2) {
                val a = pts[i]
                val b = pts[i + 1]
                val onAxis = if (horizontalSide) abs(a.y - b.y) < EPS else abs(a.x - b.x) < EPS
                if (!onAxis) continue
                val lo = if (horizontalSide) min(a.x, b.x) else min(a.y, b.y)
                val hi = if (horizontalSide) max(a.x, b.x) else max(a.y, b.y)
                if (min(hi, sHi) - max(lo, sLo) < PARALLEL_MIN_OVERLAP_PX) continue
                val off = offset(point = a, frame = frame, side = side)
                if (off < -EPS || off >= STACK_REACH_PX) continue
                // both neighbours must be perpendicular, otherwise a bend would turn diagonal
                if (!perpendicular(pts[i - 1], a, horizontalSide) || !perpendicular(b, pts[i + 2], horizontalSide)) continue
                if (off < FRAME_STANDOFF_MIN_PX) violator = true
                segs += Seg(edge = e.id, index = i, offset = off, lo = lo, hi = hi)
            }
        }
        if (!violator || segs.size > MAX_STACK) return result
        val ordered = segs.sortedWith(compareBy({ it.offset }, { it.edge.value }, { it.index }))
        val crossingsBefore = countCrossings(allRoutes(result))
        val edgeNodeBefore = detectViolations(result = result, graph = graph).edgeNode.size
        val f1Before = detectFrameViolations(result = result, graph = graph).f1

        fun assign(order: List<Seg>): Map<Seg, Int> {
            val lanes = LinkedHashMap<Seg, Int>()
            for (s in order) {
                var k = 0
                while (lanes.any { (o, lane) ->
                        lane == k && o.hi + LANE_CONFLICT_MARGIN_PX > s.lo && s.hi + LANE_CONFLICT_MARGIN_PX > o.lo
                    }
                ) {
                    k++
                }
                lanes[s] = k
            }
            return lanes
        }

        fun build(lanes: Map<Seg, Int>): LayoutResult? {
            val moved = LinkedHashMap<EdgeId, MutableList<Point>>()
            for ((seg, lane) in lanes) {
                val pts = moved.getOrPut(seg.edge) { polylinePoints(result.edges.getValue(seg.edge))!!.toMutableList() }
                val target = lanePosition(frame = frame, side = side, lane = lane)
                val cur = coord(pts[seg.index], horizontalSide)
                // a segment that already sits further out than its lane stays where it is
                val newPos = if (outer(cur = cur, target = target, side = side)) cur else target
                val limit = if (horizontalSide) result.canvas.height else result.canvas.width
                if (newPos < 0f || newPos > limit) return null
                pts[seg.index] = withCoord(pts[seg.index], horizontalSide, newPos)
                pts[seg.index + 1] = withCoord(pts[seg.index + 1], horizontalSide, newPos)
            }
            var out = result
            for ((id, pts) in moved) out = out.withRoutes(id to out.edges.getValue(id).withPoints(pts))
            return out
        }

        val head = ordered.take(MAX_PERMUTED)
        val tail = ordered.drop(MAX_PERMUTED)
        var best: LayoutResult? = null
        var bestCross = Int.MAX_VALUE
        var bestDisp = Float.MAX_VALUE
        for (perm in permutations(head)) {
            val candidate = build(assign(perm + tail)) ?: continue
            val cross = countCrossings(allRoutes(candidate))
            if (cross > crossingsBefore) continue
            if (detectViolations(result = candidate, graph = graph).edgeNode.size > edgeNodeBefore) continue
            val disp = displacement(result, candidate)
            if (cross < bestCross || (cross == bestCross && disp < bestDisp)) {
                best = candidate
                bestCross = cross
                bestDisp = disp
            }
        }
        val chosen = best ?: return result
        return if (detectFrameViolations(result = chosen, graph = graph).f1 < f1Before) chosen else result
    }

    private fun displacement(
        a: LayoutResult,
        b: LayoutResult,
    ): Float {
        var sum = 0f
        for ((id, r) in a.edges) {
            val pa = polylinePoints(r) ?: continue
            val pb = polylinePoints(b.edges[id] ?: continue) ?: continue
            if (pa.size != pb.size) continue
            for (i in pa.indices) sum += abs(pa[i].x - pb[i].x) + abs(pa[i].y - pb[i].y)
        }
        return sum
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMapIndexed { i, x -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(x) + it } }
        }

    private fun span(
        frame: Rect,
        side: Side,
    ): Pair<Float, Float> =
        if (side == Side.TOP || side == Side.BOTTOM) {
            frame.origin.x to frame.origin.x + frame.size.width
        } else {
            frame.origin.y to frame.origin.y + frame.size.height
        }

    /** Distance (px) of [point] outside the frame side; negative inside. */
    private fun offset(
        point: Point,
        frame: Rect,
        side: Side,
    ): Float =
        when (side) {
            Side.TOP -> frame.origin.y - point.y
            Side.BOTTOM -> point.y - (frame.origin.y + frame.size.height)
            Side.LEFT -> frame.origin.x - point.x
            Side.RIGHT -> point.x - (frame.origin.x + frame.size.width)
        }

    private fun lanePosition(
        frame: Rect,
        side: Side,
        lane: Int,
    ): Float {
        val d = FRAME_STANDOFF_TARGET_PX * (lane + 1)
        return when (side) {
            Side.TOP -> frame.origin.y - d
            Side.BOTTOM -> frame.origin.y + frame.size.height + d
            Side.LEFT -> frame.origin.x - d
            Side.RIGHT -> frame.origin.x + frame.size.width + d
        }
    }

    private fun coord(
        p: Point,
        horizontalSide: Boolean,
    ): Float = if (horizontalSide) p.y else p.x

    private fun withCoord(
        p: Point,
        horizontalSide: Boolean,
        v: Float,
    ): Point = if (horizontalSide) Point(x = p.x, y = v) else Point(x = v, y = p.y)

    /** True when [cur] already lies further out than [target]. */
    private fun outer(
        cur: Float,
        target: Float,
        side: Side,
    ): Boolean = if (side == Side.TOP || side == Side.LEFT) cur < target - EPS else cur > target + EPS

    /** True when p->q is perpendicular to the side direction (a segment across the side's axis). */
    private fun perpendicular(
        p: Point,
        q: Point,
        horizontalSide: Boolean,
    ): Boolean = if (horizontalSide) abs(p.x - q.x) < EPS else abs(p.y - q.y) < EPS
}
