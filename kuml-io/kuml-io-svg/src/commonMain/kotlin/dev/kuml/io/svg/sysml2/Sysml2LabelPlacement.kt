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
 * already placed label. Geometry is font independent (callers pass estimated widths).
 */
internal object Sysml2LabelPlacement {
    private const val GAP = 8f
    private const val END_MARGIN = 12f
    private const val INFLATE = 2f
    private const val MAX_SEGMENTS = 8
    private const val MAX_ITER = 20_000

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
                            (if (isAxis(seg)) 0f else 0.5f)
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
            placed[req.id] = best
        }
        return placed
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
        for (k in listOf(0, 1, -1, 2, -2)) {
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
