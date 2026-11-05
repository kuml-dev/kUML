package dev.kuml.io.latex

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point

/** Returns the mid-point of the edge route in layout coordinates, or null when empty. */
internal fun routeMidPoint(route: EdgeRoute): Pair<Float, Float>? =
    when (route) {
        is EdgeRoute.Direct -> {
            val mx = (route.source.x + route.target.x) / 2f
            val my = (route.source.y + route.target.y) / 2f
            mx to my
        }
        is EdgeRoute.OrthogonalRounded ->
            midOfPolyline(source = route.source, waypoints = route.waypoints, target = route.target)
        is EdgeRoute.TreeRounded ->
            midOfPolyline(source = route.source, waypoints = route.waypoints, target = route.target)
        is EdgeRoute.Bezier -> {
            val mx = (route.source.x + route.target.x) / 2f
            val my = (route.source.y + route.target.y) / 2f
            mx to my
        }
    }

/**
 * Returns the mid-point of a polyline defined by [source], [waypoints], and [target],
 * or null when the polyline has fewer than two points.
 *
 * The mid-point is the average of the two points flanking the centre index of the
 * assembled point list `[source] + waypoints + [target]`.
 */
internal fun midOfPolyline(
    source: Point,
    waypoints: List<Point>,
    target: Point,
): Pair<Float, Float>? {
    val allPoints =
        buildList {
            add(source)
            addAll(waypoints)
            add(target)
        }
    if (allPoints.size < 2) return null
    val mid = allPoints.size / 2
    val a = allPoints[mid - 1]
    val b = allPoints[mid]
    return (a.x + b.x) / 2f to (a.y + b.y) / 2f
}
