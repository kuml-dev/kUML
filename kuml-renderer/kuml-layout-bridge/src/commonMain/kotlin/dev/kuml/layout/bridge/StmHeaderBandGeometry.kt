package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect

/** Tolerance (px) for the segment/rectangle test: a segment that only grazes the border is not a crossing. */
private const val SEGMENT_RECT_TOLERANCE_PX = 0.5f

/**
 * True when the segment [a]-[b] passes through the interior of [r] (Liang-Barsky clip against [r] shrunk by
 * 0.5 px, so touching the border does not count). Works for any direction, also for diagonal segments.
 */
internal fun segmentIntersectsRect(
    a: Point,
    b: Point,
    r: Rect,
): Boolean {
    val xMin = r.origin.x + SEGMENT_RECT_TOLERANCE_PX
    val xMax = r.origin.x + r.size.width - SEGMENT_RECT_TOLERANCE_PX
    val yMin = r.origin.y + SEGMENT_RECT_TOLERANCE_PX
    val yMax = r.origin.y + r.size.height - SEGMENT_RECT_TOLERANCE_PX
    if (xMin >= xMax || yMin >= yMax) return false
    val dx = b.x - a.x
    val dy = b.y - a.y
    var t0 = 0f
    var t1 = 1f
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - xMin, xMax - a.x, a.y - yMin, yMax - a.y)
    for (i in 0 until 4) {
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
    return t0 < t1 || (t0 == t1 && t0 > 0f && t1 < 1f)
}

/** The points of a route that is a polyline, `null` for curved or other route kinds. */
internal fun polylinePoints(route: EdgeRoute): List<Point>? =
    when (route) {
        is EdgeRoute.Direct -> listOf(route.source, route.target)
        is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + route.target
        else -> null
    }

/** True when any segment of [pts] passes through [header]. */
internal fun crossesHeader(
    pts: List<Point>,
    header: Rect,
): Boolean = pts.zipWithNext().any { (a, b) -> segmentIntersectsRect(a = a, b = b, r = header) }
