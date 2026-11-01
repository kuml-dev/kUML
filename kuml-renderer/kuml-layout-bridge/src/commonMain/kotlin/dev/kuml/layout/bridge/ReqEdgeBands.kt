package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Post-processing of requirement diagram routes (wave 2E-a): no bundles of parallel edges.
 *
 *  1. [spreadEndpoints]: ends on one side of one card keep at least 12 px between each other and 8 px to the corners;
 *  2. [nudge]: orthogonal segments of different edges that run parallel and closer than 8 px over a common stretch are
 *     moved apart to a 10 px pitch around their median (at most three passes). A move that would run into a card, leave
 *     the canvas or fold a neighbouring segment is skipped.
 *
 * Deterministic: edges are processed in id order, ties break on the segment index.
 */
internal object ReqEdgeBands {
    const val MIN_END_GAP_PX = 12f
    const val CORNER_MARGIN_PX = 8f
    const val TRIGGER_PX = 8f
    const val PITCH_PX = 10f
    const val MAX_PASSES = 3
    const val MAX_SEGMENTS = 4000
    private const val MIN_SEGMENT_PX = 6f
    private const val MIN_OVERLAP_PX = 2f
    private const val SHRINK_PX = 1f

    private val logger = KotlinLogging.logger {}

    fun apply(
        result: LayoutResult,
        graph: LayoutGraph,
    ): LayoutResult {
        if (result.edges.isEmpty()) return result
        val ends = graph.edges.map { Triple(it.id.value, it.source.nodeId.value, it.target.nodeId.value) }
        val boxes = result.nodes.entries.associate { it.key.value to it.value.bounds }
        val routes = result.edges.entries.associateTo(LinkedHashMap()) { it.key.value to it.value }
        val spread = spreadEndpoints(routes = routes, ends = ends, boxes = boxes)
        val nudged = nudge(routes = spread.mapValues { cleaned(it.value) }, ends = ends, boxes = boxes, canvas = result.canvas)
        val out = LinkedHashMap<EdgeId, EdgeRoute>()
        for (id in result.edges.keys) out[id] = nudged.getValue(id.value)
        return result.copy(edges = out)
    }

    fun spreadEndpoints(
        routes: Map<String, EdgeRoute>,
        ends: List<Triple<String, String, String>>,
        boxes: Map<String, Rect>,
        minGap: Float = MIN_END_GAP_PX,
        cornerMargin: Float = CORNER_MARGIN_PX,
    ): Map<String, EdgeRoute> =
        EndpointSpread(minGap = minGap, sideMargin = cornerMargin).spread(routes = routes, ends = ends, boxes = boxes)

    private data class Seg(
        val edge: String,
        val index: Int,
        val horizontal: Boolean,
        val coord: Float,
        val lo: Float,
        val hi: Float,
    )

    fun nudge(
        routes: Map<String, EdgeRoute>,
        ends: List<Triple<String, String, String>>,
        boxes: Map<String, Rect>,
        canvas: Size,
        trigger: Float = TRIGGER_PX,
        pitch: Float = PITCH_PX,
    ): Map<String, EdgeRoute> {
        val endsOf = ends.associate { it.first to (it.second to it.third) }
        val ids = routes.keys.sorted()
        val pts = LinkedHashMap<String, MutableList<Point>>()
        for (id in ids) {
            val r = routes.getValue(id)
            if (r is EdgeRoute.Bezier) continue
            pts[id] = r.polylinePoints().toMutableList()
        }
        val solid = boxes.entries.sortedBy { it.key }.map { it.value }
        var moved = false
        for (pass in 0 until MAX_PASSES) {
            val segs = collect(pts)
            if (segs.size > MAX_SEGMENTS) {
                logger.debug { "requirement edge nudging skipped: ${segs.size} segments" }
                break
            }
            var movedInPass = false
            for (horizontal in listOf(true, false)) {
                for (cluster in clusters(segs = segs.filter { it.horizontal == horizontal }, trigger = trigger)) {
                    val sorted = cluster.sortedWith(compareBy({ it.coord }, { it.edge }, { it.index }))
                    val median = sorted[sorted.size / 2].coord
                    // Segments already placed in this cluster; a new one keeps `pitch` from every overlapping settled one.
                    val settled = ArrayList<Pair<Seg, Float>>()
                    for ((j, seg) in sorted.withIndex()) {
                        val list = pts.getValue(seg.edge)
                        val (srcId, tgtId) = endsOf[seg.edge] ?: ("" to "")
                        val current = liveCoord(list = list, seg = seg) ?: continue
                        val wanted = median + (j - (sorted.size - 1) / 2f) * pitch
                        val candidates = listOf(wanted) + listOf(0f, 1f, -1f, 2f, -2f, 3f, -3f).map { current + it * pitch }
                        var placed: Float? = null
                        for (c in candidates) {
                            val clear =
                                settled.all { (o, oc) ->
                                    val overlap = min(seg.hi, o.hi) - max(seg.lo, o.lo)
                                    overlap <= MIN_OVERLAP_PX || abs(c - oc) >= pitch - 0.01f
                                }
                            if (!clear) continue
                            if (abs(c - current) < 0.01f) {
                                placed = current
                                break
                            }
                            if (tryMove(
                                    list = list,
                                    seg = seg,
                                    target = c,
                                    sourceBox = boxes[srcId],
                                    targetBox = boxes[tgtId],
                                    solid = solid,
                                    canvas = canvas,
                                )
                            ) {
                                movedInPass = true
                                placed = c
                                break
                            }
                        }
                        settled += seg to (placed ?: current)
                    }
                }
            }
            if (!movedInPass) break
            moved = true
        }
        if (!moved) return routes
        val out = LinkedHashMap<String, EdgeRoute>()
        for ((id, r) in routes) out[id] = pts[id]?.let { rebuild(route = r, pts = it) } ?: r
        return out
    }

    /** The route without duplicate and collinear interior points (jogs of the endpoint spread leave some behind). */
    internal fun cleaned(route: EdgeRoute): EdgeRoute {
        if (route is EdgeRoute.Bezier || route is EdgeRoute.Direct) return route
        val pts = ArrayList<Point>()
        for (p in route.polylinePoints()) {
            val last = pts.lastOrNull()
            if (last == null || abs(last.x - p.x) > 0.01f || abs(last.y - p.y) > 0.01f) pts += p
        }
        var i = 1
        while (i < pts.size - 1) {
            val a = pts[i - 1]
            val b = pts[i]
            val c = pts[i + 1]
            val sameX = abs(a.x - b.x) < 0.01f && abs(b.x - c.x) < 0.01f
            val sameY = abs(a.y - b.y) < 0.01f && abs(b.y - c.y) < 0.01f
            if (sameX || sameY) pts.removeAt(i) else i++
        }
        return if (pts.size < 2) route else rebuild(route = route, pts = pts)
    }

    private fun collect(pts: Map<String, List<Point>>): List<Seg> {
        val out = ArrayList<Seg>()
        for ((id, list) in pts) {
            for (i in 0 until list.size - 1) {
                val a = list[i]
                val b = list[i + 1]
                if (abs(a.y - b.y) <= 0.5f && abs(a.x - b.x) > 0.5f) {
                    out += Seg(edge = id, index = i, horizontal = true, coord = a.y, lo = min(a.x, b.x), hi = max(a.x, b.x))
                } else if (abs(a.x - b.x) <= 0.5f && abs(a.y - b.y) > 0.5f) {
                    out += Seg(edge = id, index = i, horizontal = false, coord = a.x, lo = min(a.y, b.y), hi = max(a.y, b.y))
                }
            }
        }
        return out
    }

    /** Connected groups of segments of different edges that are closer than [trigger] over a common stretch. */
    private fun clusters(
        segs: List<Seg>,
        trigger: Float,
    ): List<List<Seg>> {
        val sorted = segs.sortedWith(compareBy({ it.coord }, { it.edge }, { it.index }))
        val parent = IntArray(sorted.size) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val n = parent[c]
                parent[c] = r
                c = n
            }
            return r
        }
        for (i in sorted.indices) {
            var j = i + 1
            while (j < sorted.size && sorted[j].coord - sorted[i].coord < trigger) {
                val a = sorted[i]
                val b = sorted[j]
                val overlap = min(a.hi, b.hi) - max(a.lo, b.lo)
                if (a.edge != b.edge && overlap > MIN_OVERLAP_PX) {
                    val ra = find(i)
                    val rb = find(j)
                    if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
                }
                j++
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Seg>>()
        for (i in sorted.indices) groups.getOrPut(find(i)) { ArrayList() } += sorted[i]
        return groups.values.filter { it.size >= 2 && it.map { s -> s.edge }.distinct().size >= 2 }
    }

    /** The current coordinate of [seg] in [list], or `null` if an earlier move of the same pass turned it into something else. */
    private fun liveCoord(
        list: List<Point>,
        seg: Seg,
    ): Float? {
        if (seg.index + 1 >= list.size) return null
        val a = list[seg.index]
        val b = list[seg.index + 1]
        return if (seg.horizontal) {
            if (abs(a.y - b.y) <= 0.5f) a.y else null
        } else {
            if (abs(a.x - b.x) <= 0.5f) a.x else null
        }
    }

    /** Moves segment [seg] of [list] to [target] if that keeps the route valid; returns whether it moved. */
    private fun tryMove(
        list: MutableList<Point>,
        seg: Seg,
        target: Float,
        sourceBox: Rect?,
        targetBox: Rect?,
        solid: List<Rect>,
        canvas: Size,
    ): Boolean {
        val i = seg.index
        val n = list.size
        if (i + 1 >= n) return false
        val a = list[i]
        val b = list[i + 1]
        val horizontal = seg.horizontal
        // The segment may have changed in an earlier move of the same pass.
        val live = if (horizontal) abs(a.y - b.y) <= 0.5f else abs(a.x - b.x) <= 0.5f
        if (!live) return false
        val touchesStart = i == 0
        val touchesEnd = i + 1 == n - 1
        if (touchesStart && touchesEnd) return false
        // Ends slide along their box side.
        for ((touches, box) in listOf(touchesStart to sourceBox, touchesEnd to targetBox)) {
            if (!touches) continue
            if (box == null) return false
            val lo = (if (horizontal) box.origin.y else box.origin.x) + CORNER_MARGIN_PX
            val hi = (if (horizontal) box.origin.y + box.size.height else box.origin.x + box.size.width) - CORNER_MARGIN_PX
            if (target < lo || target > hi) return false
        }
        val limit = if (horizontal) canvas.height else canvas.width
        if (target < -0.5f || target > limit + 0.5f) return false

        fun at(
            p: Point,
            c: Float,
        ) = if (horizontal) p.copy(y = c) else p.copy(x = c)

        fun coordOf(p: Point) = if (horizontal) p.y else p.x

        // Neighbouring segments run across the moved one; they may change direction (the route stays orthogonal) but
        // keep a minimum length.
        val old = coordOf(a)
        val changed = ArrayList<Pair<Point, Point>>()
        changed += at(a, target) to at(b, target)
        if (i > 0) {
            val p = list[i - 1]
            if (abs(target - coordOf(p)) < MIN_SEGMENT_PX) return false
            changed += p to at(a, target)
        }
        if (i + 2 < n) {
            val q = list[i + 2]
            if (abs(target - coordOf(q)) < MIN_SEGMENT_PX) return false
            changed += at(b, target) to q
        }
        for ((p, q) in changed) {
            for (box in solid) if (hits(p = p, q = q, box = box)) return false
        }
        list[i] = at(a, target)
        list[i + 1] = at(b, target)
        return true
    }

    /** Does the axis-parallel segment p-q run through the interior of [box] (shrunk by one pixel)? */
    private fun hits(
        p: Point,
        q: Point,
        box: Rect,
    ): Boolean {
        val l = box.origin.x + SHRINK_PX
        val r = box.origin.x + box.size.width - SHRINK_PX
        val t = box.origin.y + SHRINK_PX
        val b = box.origin.y + box.size.height - SHRINK_PX
        if (r <= l || b <= t) return false
        val x0 = min(p.x, q.x)
        val x1 = max(p.x, q.x)
        val y0 = min(p.y, q.y)
        val y1 = max(p.y, q.y)
        return x1 > l && x0 < r && y1 > t && y0 < b
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
                    EdgeRoute.OrthogonalRounded(source = pts.first(), target = pts.last(), waypoints = way, cornerRadiusPx = 8f)
                }
            is EdgeRoute.OrthogonalRounded -> route.copy(source = pts.first(), target = pts.last(), waypoints = way)
            is EdgeRoute.TreeRounded -> route.copy(source = pts.first(), target = pts.last(), waypoints = way)
            is EdgeRoute.Bezier -> route
        }
    }
}
