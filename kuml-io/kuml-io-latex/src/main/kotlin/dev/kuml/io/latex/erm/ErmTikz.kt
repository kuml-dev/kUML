@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.io.latex.fmtCoord
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import kotlin.math.sqrt

/** Notation-neutral geometry helpers shared by the Martin, Bachman and IDEF1X glyph code. */
internal object ErmTikz {
    /** Unit direction pointing away from the entity into the edge, at the source end and at the target end. */
    data class EndDirections(
        val source: Pair<Float, Float>,
        val target: Pair<Float, Float>,
    )

    /** The directions the glyphs extend along, from the first and last segment of [route]. */
    fun endDirections(route: EdgeRoute): EndDirections {
        val (afterSource, beforeTarget) =
            when (route) {
                is EdgeRoute.Direct -> route.target to route.source
                is EdgeRoute.OrthogonalRounded -> neighbours(route.waypoints, route)
                is EdgeRoute.TreeRounded -> neighbours(route.waypoints, route)
                is EdgeRoute.Bezier ->
                    (route.controlPoints.firstOrNull() ?: route.target) to (route.controlPoints.lastOrNull() ?: route.source)
            }
        return EndDirections(
            source = unit(from = route.source, to = afterSource),
            target = unit(from = route.target, to = beforeTarget),
        )
    }

    private fun neighbours(
        points: List<Point>,
        route: EdgeRoute,
    ): Pair<Point, Point> = (points.firstOrNull() ?: route.target) to (points.lastOrNull() ?: route.source)

    private fun unit(
        from: Point,
        to: Point,
    ): Pair<Float, Float> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        return if (len < 1e-3f) 1f to 0f else dx / len to dy / len
    }

    /** TikZ coordinate of a layout point; negates y for the axis flip. */
    fun pt(
        x: Float,
        y: Float,
    ): String = "(${fmtCoord(x)}pt, ${fmtCoord(-y)}pt)"
}
