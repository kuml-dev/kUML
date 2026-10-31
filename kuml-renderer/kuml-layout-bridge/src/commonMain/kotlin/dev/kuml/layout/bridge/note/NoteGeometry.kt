package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal val Rect.left: Float get() = origin.x
internal val Rect.top: Float get() = origin.y
internal val Rect.right: Float get() = origin.x + size.width
internal val Rect.bottom: Float get() = origin.y + size.height
internal val Rect.cx: Float get() = origin.x + size.width / 2f
internal val Rect.cy: Float get() = origin.y + size.height / 2f

internal fun rectOf(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Rect = Rect(origin = Point(x = left, y = top), size = Size(width = right - left, height = bottom - top))

internal fun Rect.inflated(d: Float): Rect = rectOf(left = left - d, top = top - d, right = right + d, bottom = bottom + d)

internal fun Rect.intersects(o: Rect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

internal fun Rect.union(o: Rect): Rect =
    rectOf(left = min(left, o.left), top = min(top, o.top), right = max(right, o.right), bottom = max(bottom, o.bottom))

internal fun Rect.contains(p: Point): Boolean = p.x >= left && p.x <= right && p.y >= top && p.y <= bottom

internal fun distance(
    a: Point,
    b: Point,
): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

internal fun polylineLength(pts: List<Point>): Float {
    var sum = 0f
    for (i in 0 until pts.size - 1) sum += distance(a = pts[i], b = pts[i + 1])
    return sum
}

/** Point at half the arc length of [pts]. */
internal fun polylineMidpoint(pts: List<Point>): Point =
    if (pts.size <
        2
    ) {
        pts.first()
    } else {
        polylineAt(pts = pts, at = polylineLength(pts) / 2f)
    }

/** Point at arc length [at] along [pts] (clamped to the end). */
internal fun polylineAt(
    pts: List<Point>,
    at: Float,
): Point {
    if (pts.size < 2) return pts.first()
    val half = at
    var walked = 0f
    for (i in 0 until pts.size - 1) {
        val len = distance(a = pts[i], b = pts[i + 1])
        if (walked + len >= half && len > 0f) {
            val t = (half - walked) / len
            return Point(x = pts[i].x + (pts[i + 1].x - pts[i].x) * t, y = pts[i].y + (pts[i + 1].y - pts[i].y) * t)
        }
        walked += len
    }
    return pts.last()
}

/** Liang-Barsky: does the segment [a]-[b] cross the (closed) rectangle [r]? */
internal fun segmentIntersectsRect(
    a: Point,
    b: Point,
    r: Rect,
): Boolean {
    var t0 = 0f
    var t1 = 1f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - r.left, r.right - a.x, a.y - r.top, r.bottom - a.y)
    for (i in 0..3) {
        if (p[i] == 0f) {
            if (q[i] < 0f) return false
        } else {
            val t = q[i] / p[i]
            if (p[i] < 0f) {
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

private fun cross(
    o: Point,
    a: Point,
    b: Point,
): Float = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

/** Proper crossing of two segments (touching endpoints do not count). */
internal fun segmentsCross(
    a: Point,
    b: Point,
    c: Point,
    d: Point,
): Boolean {
    val d1 = cross(o = a, a = b, b = c)
    val d2 = cross(o = a, a = b, b = d)
    val d3 = cross(o = c, a = d, b = a)
    val d4 = cross(o = c, a = d, b = b)
    return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
}

/**
 * Length over which two nearly parallel segments run on top of each other (closer than [tolerance]).
 * 0 if they are not (nearly) collinear.
 */
internal fun collinearOverlap(
    a: Point,
    b: Point,
    c: Point,
    d: Point,
    tolerance: Float = 4f,
): Float {
    val len = distance(a = a, b = b)
    if (len < 1e-3f) return 0f
    val ux = (b.x - a.x) / len
    val uy = (b.y - a.y) / len
    // perpendicular distance of c and d to the line a-b
    val dc = abs((c.x - a.x) * uy - (c.y - a.y) * ux)
    val dd = abs((d.x - a.x) * uy - (d.y - a.y) * ux)
    if (dc > tolerance || dd > tolerance) return 0f
    val tc = (c.x - a.x) * ux + (c.y - a.y) * uy
    val td = (d.x - a.x) * ux + (d.y - a.y) * uy
    val lo = max(0f, min(tc, td))
    val hi = min(len, max(tc, td))
    return max(0f, hi - lo)
}

/**
 * True if the segment [a]-[b] runs along the segment [c]-[d]: both closer than [tolerance] and overlapping over
 * more than [minLen]. The last [freeTail] px of [a]-[b] before [b] are ignored when [tailFree] is set (the anchor end
 * touches its target on purpose).
 */
internal fun runsAlong(
    a: Point,
    b: Point,
    c: Point,
    d: Point,
    tailFree: Boolean = false,
    tolerance: Float = RUN_ALONG_TOLERANCE,
    minLen: Float = RUN_ALONG_MIN_LEN,
    freeTail: Float = RUN_ALONG_FREE_TAIL,
): Boolean {
    var end = b
    if (tailFree) {
        val len = distance(a = a, b = b)
        if (len <= freeTail) return false
        val t = (len - freeTail) / len
        end = Point(x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t)
    }
    return collinearOverlap(a = a, b = end, c = c, d = d, tolerance = tolerance) > minLen
}

/** Distance from [p] to the segment [a]-[b]. */
internal fun distanceToSegment(
    p: Point,
    a: Point,
    b: Point,
): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-6f) return distance(a = p, b = a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return distance(a = p, b = Point(x = a.x + dx * t, y = a.y + dy * t))
}

/** The four border segments of [r] as point pairs. */
internal fun rectEdges(r: Rect): List<Pair<Point, Point>> {
    val tl = Point(x = r.left, y = r.top)
    val tr = Point(x = r.right, y = r.top)
    val br = Point(x = r.right, y = r.bottom)
    val bl = Point(x = r.left, y = r.bottom)
    return listOf(tl to tr, tr to br, br to bl, bl to tl)
}

internal const val RUN_ALONG_TOLERANCE: Float = 3f
internal const val RUN_ALONG_MIN_LEN: Float = 6f
internal const val RUN_ALONG_FREE_TAIL: Float = 4f

/** Nearest point on the border of [r] to [p] (or [p] clamped into [r] projected to the border when inside). */
internal fun nearestBorderPoint(
    r: Rect,
    p: Point,
): Point {
    val cx = p.x.coerceIn(r.left, r.right)
    val cy = p.y.coerceIn(r.top, r.bottom)
    if (cx != p.x || cy != p.y) return Point(x = cx, y = cy) // outside: clamped point lies on the border
    val dl = p.x - r.left
    val dr = r.right - p.x
    val dt = p.y - r.top
    val db = r.bottom - p.y
    val m = min(min(dl, dr), min(dt, db))
    return when (m) {
        dl -> Point(x = r.left, y = p.y)
        dr -> Point(x = r.right, y = p.y)
        dt -> Point(x = p.x, y = r.top)
        else -> Point(x = p.x, y = r.bottom)
    }
}

internal fun EdgeRoute.shifted(
    dx: Float,
    dy: Float,
): EdgeRoute {
    fun Point.s() = Point(x = x + dx, y = y + dy)
    return when (this) {
        is EdgeRoute.Direct -> copy(source = source.s(), target = target.s())
        is EdgeRoute.OrthogonalRounded -> copy(source = source.s(), target = target.s(), waypoints = waypoints.map { it.s() })
        is EdgeRoute.TreeRounded -> copy(source = source.s(), target = target.s(), waypoints = waypoints.map { it.s() })
        is EdgeRoute.Bezier -> copy(source = source.s(), target = target.s(), controlPoints = controlPoints.map { it.s() })
    }
}

/**
 * Moves everything in this layout by ([dx], [dy]) and grows the canvas by the same amount. Used when
 * a note had to be placed left of or above the existing content. [shiftPorts] is false for layouts whose port
 * positions are local to their node (IBD).
 */
public fun LayoutResult.translated(
    dx: Float,
    dy: Float,
    shiftPorts: Boolean = true,
): LayoutResult {
    if (dx == 0f && dy == 0f) return this

    fun Point.s() = Point(x = x + dx, y = y + dy)

    fun Rect.s() = Rect(origin = origin.s(), size = size)
    return copy(
        canvas = Size(width = canvas.width + dx, height = canvas.height + dy),
        nodes =
            nodes.mapValues { (_, n) ->
                NodeLayout(
                    bounds = n.bounds.s(),
                    // IBD stores port positions relative to their part: moving the part moves them already.
                    ports = if (shiftPorts) n.ports.mapValues { it.value.s() } else n.ports,
                    labels = n.labels.mapValues { it.value.s() },
                )
            },
        edges = edges.mapValues { it.value.shifted(dx = dx, dy = dy) },
        groups = groups.mapValues { GroupLayout(bounds = it.value.bounds.s()) },
    )
}
