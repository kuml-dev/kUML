package dev.kuml.io.svg.sysml2

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Label of one edge to place: [w] x [h] box, [polyline] of the edge's own drawn line. */
internal data class LabelRequest(
    val id: String,
    val polyline: List<Point>,
    val w: Float,
    val h: Float,
)

/**
 * Deterministic edge-label placement for SysML 2 requirement, use-case and activity diagrams
 * (wave C2b). A label is centred **on** its own line (the line runs through the middle of the
 * box, which gets an opaque backdrop). The longest segments are tried first; on a collision the
 * label slides along the segment by `+-(labelW + 8)`, and finally moves to the next segment.
 * A candidate is rejected in favour of a cheaper one if it touches another edge, a node, or an
 * already placed label; hard obstacles (wave 2E-a: group frame strips) cost a lot, but a label is never dropped. Geometry is font independent (callers pass estimated widths).
 */
internal object Sysml2LabelPlacement {
    private const val GAP = 8f
    private const val END_MARGIN = 12f
    private const val INFLATE = 2f
    private const val MAX_SEGMENTS = 8
    private const val MAX_ITER = 20_000
    private const val HARD_COST = 1000f

    private data class Seg(
        val edge: String,
        val a: Point,
        val b: Point,
    )

    /** Places all [requests] (processed in id order). Returns the centre box per edge id. */
    fun placeAll(
        requests: List<LabelRequest>,
        allRoutes: Map<String, List<Point>>,
        obstacles: List<Rect>,
        hardObstacles: List<Rect> = emptyList(),
    ): Map<String, Rect> {
        val segs = ArrayList<Seg>()
        for ((id, pl) in allRoutes.entries.sortedBy { it.key }) {
            for (i in 0 until pl.size - 1) segs += Seg(edge = id, a = pl[i], b = pl[i + 1])
        }
        val placed = LinkedHashMap<String, Rect>()
        var iter = 0
        for (req in requests.sortedBy { it.id }) {
            val pl = req.polyline
            if (pl.size < 2) continue
            val own = (0 until pl.size - 1).map { Seg(edge = req.id, a = pl[it], b = pl[it + 1]) }
            val order = own.sortedWith(compareBy({ if (isAxis(it)) 0 else 1 }, { -length(it) })).take(MAX_SEGMENTS)
            var best: Rect? = null
            var bestCost = Float.MAX_VALUE
            search@ for (seg in order) {
                for (c in candidates(seg = seg, req = req)) {
                    if (++iter > MAX_ITER) break@search
                    val cost =
                        cost(r = c, ownId = req.id, segs = segs, obstacles = obstacles, placed = placed.values) +
                            (if (isAxis(seg)) 0f else 0.5f) +
                            (if (hardObstacles.any { overlaps(a = c, b = it) }) HARD_COST else 0f)
                    if (cost < bestCost) {
                        bestCost = cost
                        best = c
                        if (cost == 0f) break@search
                    }
                }
            }
            if (best == null) {
                val m = midpoint(order.firstOrNull() ?: own.first())
                best =
                    Rect(
                        origin = Point(x = m.x - req.w / 2f, y = m.y - req.h / 2f),
                        size = dev.kuml.layout.Size(width = req.w, height = req.h),
                    )
            }
            // Wave 2E-a: a label is never dropped. If every candidate touches a hard obstacle (a group frame strip),
            // the cheapest one is kept: a label next to a frame is better than a stereotype edge without its label.
            placed[req.id] = best
        }
        return stackSamePairLabels(placed = placed, routes = allRoutes, nodes = obstacles)
    }

    /** Gap between two stacked labels of one node pair (Nacharbeit 49). */
    private const val STACK_GAP = 4f

    /** Tolerance when deciding which node box an edge end touches. */
    private const val ENDPOINT_TOL = 3f

    /**
     * Nacharbeit 49: two labels of edges between the **same pair of nodes** (e.g. `derive` and `trace`) that collide are
     * stacked perpendicular to the edge direction around their common centre, so neither hides the other. The order is
     * `derive`, `trace`, then the rest by edge id; each label keeps its size. Groups without a collision, labels of a
     * single edge and edges whose ends touch no node box stay exactly where [placeAll] put them. Deterministic: ids are
     * sorted, no random input. The stack is not re-checked against group frame strips: it stays within about one label
     * height of the pair's own lines.
     */
    internal fun stackSamePairLabels(
        placed: Map<String, Rect>,
        routes: Map<String, List<Point>>,
        nodes: List<Rect>,
    ): Map<String, Rect> {
        if (placed.size < 2 || nodes.isEmpty()) return placed

        fun nodeAt(p: Point): Int? =
            nodes.indices.firstOrNull { i ->
                val r = nodes[i]
                p.x >= r.origin.x - ENDPOINT_TOL &&
                    p.x <= r.origin.x + r.size.width + ENDPOINT_TOL &&
                    p.y >= r.origin.y - ENDPOINT_TOL &&
                    p.y <= r.origin.y + r.size.height + ENDPOINT_TOL
            }
        val byPair = LinkedHashMap<Pair<Int, Int>, MutableList<String>>()
        for (id in placed.keys.sorted()) {
            val route = routes[id] ?: continue
            if (route.size < 2) continue
            val a = nodeAt(route.first()) ?: continue
            val b = nodeAt(route.last()) ?: continue
            if (a == b) continue
            byPair.getOrPut(min(a, b) to max(a, b)) { mutableListOf() } += id
        }
        val out = LinkedHashMap(placed)
        for (ids in byPair.values) {
            if (ids.size < 2) continue
            val collides =
                ids.indices.any { i ->
                    (i + 1 until ids.size).any { j ->
                        overlaps(a = inflate(r = placed.getValue(ids[i]), d = INFLATE), b = placed.getValue(ids[j]))
                    }
                }
            if (!collides) continue
            val ordered = ids.sortedWith(compareBy({ stackRank(it) }, { it }))
            val rects = ordered.map { placed.getValue(it) }
            val cx = rects.map { it.origin.x + it.size.width / 2f }.average().toFloat()
            val cy = rects.map { it.origin.y + it.size.height / 2f }.average().toFloat()
            // Perpendicular to the first label's own line: a horizontal route stacks vertically, a vertical one side by side.
            val horizontalRoute = isHorizontalNear(route = routes.getValue(ordered.first()), x = cx, y = cy)
            if (horizontalRoute) {
                var top = cy - (rects.sumOf { it.size.height.toDouble() }.toFloat() + STACK_GAP * (rects.size - 1)) / 2f
                for ((k, id) in ordered.withIndex()) {
                    val r = rects[k]
                    out[id] = Rect(origin = Point(x = cx - r.size.width / 2f, y = top), size = r.size)
                    top += r.size.height + STACK_GAP
                }
            } else {
                var left = cx - (rects.sumOf { it.size.width.toDouble() }.toFloat() + STACK_GAP * (rects.size - 1)) / 2f
                for ((k, id) in ordered.withIndex()) {
                    val r = rects[k]
                    out[id] = Rect(origin = Point(x = left, y = cy - r.size.height / 2f), size = r.size)
                    left += r.size.width + STACK_GAP
                }
            }
        }
        return out
    }

    private fun stackRank(id: String): Int =
        when {
            id.startsWith("derive:") -> 0
            id.startsWith("trace:") -> 1
            else -> 2
        }

    /** True when the segment of [route] nearest to ([x], [y]) is closer to horizontal than to vertical. */
    private fun isHorizontalNear(
        route: List<Point>,
        x: Float,
        y: Float,
    ): Boolean {
        var bestDist = Float.MAX_VALUE
        var horizontal = true
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            val d = abs(mx - x) + abs(my - y)
            if (d < bestDist) {
                bestDist = d
                horizontal = abs(b.x - a.x) >= abs(b.y - a.y)
            }
        }
        return horizontal
    }

    private fun candidates(
        seg: Seg,
        req: LabelRequest,
    ): List<Rect> {
        val len = length(seg)
        if (len < 0.01f) return emptyList()
        val ux = (seg.b.x - seg.a.x) / len
        val uy = (seg.b.y - seg.a.y) / len
        val along = abs(ux) * req.w + abs(uy) * req.h
        val step = along + GAP
        val lo = min(along / 2f + END_MARGIN, len / 2f)
        val hi = max(len - along / 2f - END_MARGIN, len / 2f)
        val ts = ArrayList<Float>()
        for (k in listOf(0, 1, -1, 2, -2, 3, -3, 4, -4)) {
            val t = len / 2f + k * step
            if (t in lo..hi) ts += t
        }
        if (ts.isEmpty()) ts += len / 2f
        return ts.map { t ->
            val cx = seg.a.x + ux * t
            val cy = seg.a.y + uy * t
            Rect(origin = Point(x = cx - req.w / 2f, y = cy - req.h / 2f), size = dev.kuml.layout.Size(width = req.w, height = req.h))
        }
    }

    private fun cost(
        r: Rect,
        ownId: String,
        segs: List<Seg>,
        obstacles: List<Rect>,
        placed: Collection<Rect>,
    ): Float {
        var cost = 0f
        val ir = inflate(r = r, d = INFLATE)
        for (p in placed) if (overlaps(a = ir, b = p)) cost += 100f
        for (o in obstacles) if (overlaps(a = r, b = o)) cost += 10f
        for (s in segs) {
            if (s.edge == ownId) continue
            if (segIntersects(p = s.a, q = s.b, r = ir)) cost += 20f
        }
        return cost
    }

    internal fun inflate(
        r: Rect,
        d: Float,
    ): Rect =
        Rect(
            origin = Point(x = r.origin.x - d, y = r.origin.y - d),
            size =
                dev.kuml.layout.Size(
                    width = r.size.width + 2 * d,
                    height =
                        r.size.height + 2 * d,
                ),
        )

    internal fun overlaps(
        a: Rect,
        b: Rect,
    ): Boolean =
        a.origin.x < b.origin.x + b.size.width &&
            b.origin.x < a.origin.x + a.size.width &&
            a.origin.y < b.origin.y + b.size.height &&
            b.origin.y < a.origin.y + a.size.height

    /** Segment / rectangle intersection (slab test); touching counts. */
    internal fun segIntersects(
        p: Point,
        q: Point,
        r: Rect,
    ): Boolean {
        var t0 = 0f
        var t1 = 1f
        val dx = q.x - p.x
        val dy = q.y - p.y

        fun clip(
            pk: Float,
            qk: Float,
        ): Boolean {
            if (abs(pk) < 1e-6f) return qk >= 0f
            val t = qk / pk
            if (pk < 0f) {
                if (t > t1) return false
                if (t > t0) t0 = t
            } else {
                if (t < t0) return false
                if (t < t1) t1 = t
            }
            return true
        }
        return clip(pk = -dx, qk = p.x - r.origin.x) &&
            clip(pk = dx, qk = r.origin.x + r.size.width - p.x) &&
            clip(pk = -dy, qk = p.y - r.origin.y) &&
            clip(pk = dy, qk = r.origin.y + r.size.height - p.y)
    }

    private fun isAxis(s: Seg): Boolean = abs(s.a.x - s.b.x) <= 0.5f || abs(s.a.y - s.b.y) <= 0.5f

    private fun length(s: Seg): Float = sqrt((s.b.x - s.a.x) * (s.b.x - s.a.x) + (s.b.y - s.a.y) * (s.b.y - s.a.y))

    private fun midpoint(s: Seg): Point = Point(x = (s.a.x + s.b.x) / 2f, y = (s.a.y + s.b.y) / 2f)
}
