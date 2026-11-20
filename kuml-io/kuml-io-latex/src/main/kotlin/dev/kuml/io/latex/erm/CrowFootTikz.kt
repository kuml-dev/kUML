@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.fmtCoord
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import kotlin.math.sqrt

/**
 * Crow's-foot (Martin) cardinality glyphs as TikZ primitives, with the same offsets as the SVG renderer
 * (`ErmMartinEdgeSvg`): a crow's foot for "many", then an open circle for "optional" or a bar for "mandatory".
 * All geometry is computed in layout coordinates (y down); [pt] flips y on emission.
 */
internal object CrowFootTikz {
    /** Distance from the entity border to the crow's-foot apex. */
    const val CROWFOOT_LEN_PX: Float = 14f

    /** Half-spread of the two outer crow's-foot prongs, perpendicular to the line. */
    const val CROWFOOT_SPREAD_PX: Float = 6f

    /** Gap between the crow's-foot apex (or the entity border) and the one/zero marker. */
    const val MARKER_GAP_PX: Float = 6f

    /** Half-length of the perpendicular "mandatory" tick mark. */
    const val BAR_HALF_PX: Float = 5f

    /** Radius of the "optional" open-circle marker. */
    const val CIRCLE_RADIUS_PX: Float = 4f

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

    /** What a glyph consists of, for tests: crow's-foot flag and marker kind. */
    internal fun hasCrowFoot(c: Cardinality): Boolean = c.many

    /** Appends the glyph of [cardinality] at [anchor] (a node border point) extending along [dir] (away from the node). */
    fun appendGlyph(
        anchor: Point,
        dir: Pair<Float, Float>,
        cardinality: Cardinality,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val (dx, dy) = dir
        val px = -dy
        val py = dx
        val ind = options.indent
        var innerOffset = 0f
        if (cardinality.many) {
            val ax = anchor.x + dx * CROWFOOT_LEN_PX
            val ay = anchor.y + dy * CROWFOOT_LEN_PX
            out.appendLine(
                "$ind\\draw[line width=0.6pt] ${pt(ax, ay)} -- ${pt(anchor.x, anchor.y)};",
            )
            out.appendLine(
                "$ind\\draw[line width=0.6pt] ${pt(anchor.x + px * CROWFOOT_SPREAD_PX, anchor.y + py * CROWFOOT_SPREAD_PX)} -- " +
                    "${pt(ax, ay)} -- ${pt(anchor.x - px * CROWFOOT_SPREAD_PX, anchor.y - py * CROWFOOT_SPREAD_PX)};",
            )
            innerOffset = CROWFOOT_LEN_PX
        }
        val markerOffset = innerOffset + MARKER_GAP_PX
        val mx = anchor.x + dx * markerOffset
        val my = anchor.y + dy * markerOffset
        if (cardinality.optional) {
            out.appendLine(
                "$ind\\draw[line width=0.6pt, fill=white] ${pt(mx + dx * CIRCLE_RADIUS_PX, my + dy * CIRCLE_RADIUS_PX)} " +
                    "circle (${fmtCoord(CIRCLE_RADIUS_PX)}pt);",
            )
        } else {
            out.appendLine(
                "$ind\\draw[line width=0.6pt] ${pt(mx + px * BAR_HALF_PX, my + py * BAR_HALF_PX)} -- " +
                    "${pt(mx - px * BAR_HALF_PX, my - py * BAR_HALF_PX)};",
            )
        }
    }

    /** TikZ coordinate of a layout point; negates y for the axis flip. */
    fun pt(
        x: Float,
        y: Float,
    ): String = "(${fmtCoord(x)}pt, ${fmtCoord(-y)}pt)"
}
