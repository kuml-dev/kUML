package dev.kuml.layout

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * All stops of a route in drawing order: [EdgeRoute.source], then the
 * style-specific intermediate points ([EdgeRoute.OrthogonalRounded.waypoints]
 * / [EdgeRoute.TreeRounded.waypoints] / [EdgeRoute.Bezier.controlPoints]),
 * then [EdgeRoute.target]. [EdgeRoute.Direct] has no intermediate points.
 *
 * For [EdgeRoute.Bezier], this is the **control-point polygon**, not a
 * sampled curve — a documented approximation (see [arcLengthMidpoint]).
 */
public fun EdgeRoute.polylinePoints(): List<Point> =
    when (this) {
        is EdgeRoute.Direct -> listOf(source, target)
        is EdgeRoute.OrthogonalRounded ->
            buildList(capacity = waypoints.size + 2) {
                add(source)
                addAll(waypoints)
                add(target)
            }
        is EdgeRoute.TreeRounded ->
            buildList(capacity = waypoints.size + 2) {
                add(source)
                addAll(waypoints)
                add(target)
            }
        is EdgeRoute.Bezier ->
            buildList(capacity = controlPoints.size + 2) {
                add(source)
                addAll(controlPoints)
                add(target)
            }
    }

/**
 * The point at half the **arc length** of this route — deliberately NOT the
 * middle waypoint by index. ELK routinely produces orthogonal routes with
 * very unequal segment lengths (a 200px vertical run followed by a 10px
 * horizontal jog); picking the middle index would land the midpoint at that
 * short segment and visibly jump around whenever the layout shifts. Walking
 * cumulative arc length instead gives a point that always lands on the
 * longest visual stretch of the route and moves smoothly as the layout
 * changes.
 *
 * For [EdgeRoute.Bezier], the arc length is approximated over the
 * **control-point polygon** ([polylinePoints]) rather than the true curve —
 * documented, not computed exactly; the true curve length requires numeric
 * integration this function deliberately avoids.
 *
 * A route whose total length is exactly `0` (all points coincide, or a
 * degenerate single-point route) returns [EdgeRoute.source] rather than
 * dividing by zero.
 */
public fun EdgeRoute.arcLengthMidpoint(): Point {
    val points = polylinePoints()
    if (points.size < 2) return source

    val segmentLengths = FloatArray(points.size - 1)
    var total = 0f
    for (i in 0 until points.size - 1) {
        val len = distanceBetween(a = points[i], b = points[i + 1])
        segmentLengths[i] = len
        total += len
    }
    if (total <= 0f) return source

    val halfLength = total / 2f
    var walked = 0f
    for (i in segmentLengths.indices) {
        val segLen = segmentLengths[i]
        if (walked + segLen >= halfLength) {
            val remaining = halfLength - walked
            val t = if (segLen > 0f) remaining / segLen else 0f
            val p0 = points[i]
            val p1 = points[i + 1]
            return Point(x = p0.x + (p1.x - p0.x) * t, y = p0.y + (p1.y - p0.y) * t)
        }
        walked += segLen
    }
    // Floating-point rounding safety net — should be unreachable given the loop
    // invariant above (the final iteration's condition is `walked + segLen >=
    // halfLength` with `walked` approaching `total` and `halfLength == total / 2`).
    return points.last()
}

/**
 * The point where the ray from this rectangle's center through [from]
 * crosses the rectangle's border (slab method).
 *
 * [from] exactly at the center, or anywhere inside (or on the border of)
 * the rectangle, returns the center itself — there is no meaningful
 * "outward" direction to intersect in that case.
 */
public fun Rect.borderIntersectionTowards(from: Point): Point {
    val centerX = origin.x + size.width / 2f
    val centerY = origin.y + size.height / 2f
    val center = Point(x = centerX, y = centerY)

    val left = origin.x
    val right = origin.x + size.width
    val top = origin.y
    val bottom = origin.y + size.height
    if (from.x in left..right && from.y in top..bottom) return center

    val dx = from.x - centerX
    val dy = from.y - centerY
    if (dx == 0f && dy == 0f) return center

    val halfWidth = size.width / 2f
    val halfHeight = size.height / 2f
    val scaleX = if (dx != 0f) halfWidth / abs(dx) else Float.POSITIVE_INFINITY
    val scaleY = if (dy != 0f) halfHeight / abs(dy) else Float.POSITIVE_INFINITY
    val scale = min(scaleX, scaleY)
    return Point(x = centerX + dx * scale, y = centerY + dy * scale)
}

private fun distanceBetween(
    a: Point,
    b: Point,
): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}
