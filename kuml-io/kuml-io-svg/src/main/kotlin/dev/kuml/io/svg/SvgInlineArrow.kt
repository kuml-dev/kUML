package dev.kuml.io.svg

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.renderer.theme.core.KumlTheme
import kotlin.math.sqrt

/*
 * Renders arrowheads as inline SVG geometry (polygon or path elements)
 * directly adjacent to the edge line/path in the SVG output.
 *
 * WHY NOT <marker>: SVG `url(#id)` marker references require the marker
 * element to be resolvable in the rendering document's ID namespace. When
 * SVGs are injected into Obsidian (or similar apps) via DOMParser +
 * appendChild, the `<defs>/<marker>` IDs may not be registered in the host
 * HTML document's ID table (Electron adoption bug), causing arrowheads to
 * silently disappear in reading/preview mode while they remain visible in
 * live-preview/edit mode (which uses a different rendering path).
 *
 * Inline geometry has no such dependency: it is plain SVG output that works
 * in every SVG rendering context.
 */

/**
 * Arrow style — maps 1-to-1 to the six former `<marker>` IDs.
 *
 * - [OPEN] / [OPEN_MUTED]: open chevron (two lines, no fill).
 * - [TRIANGLE] / [TRIANGLE_MUTED]: hollow triangle (closed, background fill).
 * - [FILLED] / [FILLED_MUTED]: solid filled triangle.
 */
internal enum class ArrowStyle {
    OPEN,
    OPEN_MUTED,
    TRIANGLE,
    TRIANGLE_MUTED,
    FILLED,
    FILLED_MUTED,
}

/** Length from arrowhead tip to its base, in pixels. */
private const val ARROW_LEN = 12f

/** Half-width of the arrowhead at its base, in pixels. */
private const val ARROW_WING = 5f

/**
 * Returns the two points that define the direction of the last edge segment:
 * `(prevPoint, endPoint)`. The arrowhead tip is placed at `endPoint`,
 * oriented along `prevPoint → endPoint`.
 */
internal fun EdgeRoute.arrowDirection(): Pair<Point, Point> =
    when (this) {
        is EdgeRoute.Direct ->
            source to target
        is EdgeRoute.OrthogonalRounded ->
            (waypoints.lastOrNull() ?: source) to target
        is EdgeRoute.TreeRounded ->
            (waypoints.lastOrNull() ?: source) to target
        is EdgeRoute.Bezier ->
            (controlPoints.lastOrNull() ?: source) to target
    }

/**
 * Appends an inline arrowhead element (polygon or path) to [builder].
 *
 * The tip of the arrowhead is at [tip]; the direction is computed from the
 * [from] → [tip] vector. Styles use inline `style="..."` attributes (not
 * presentation attributes) so they survive any CSS cascade from the host
 * document.
 *
 * @param from Second-to-last point on the route — determines direction.
 * @param tip  End point of the route — where the arrowhead tip is placed.
 * @param style Which arrow shape / colour to draw.
 * @param theme Theme for colour lookup.
 * @param builder SVG builder for the edges group.
 */
internal fun renderInlineArrow(
    from: Point,
    tip: Point,
    style: ArrowStyle,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val dx = tip.x - from.x
    val dy = tip.y - from.y
    val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    if (len < 0.001f) return

    // Unit direction vector (tip direction) and its perpendicular.
    val nx = dx / len
    val ny = dy / len
    val px = -ny
    val py = nx

    // Arrowhead base corners (left and right wing at the base of the triangle).
    val lx = tip.x - nx * ARROW_LEN + px * ARROW_WING
    val ly = tip.y - ny * ARROW_LEN + py * ARROW_WING
    val rx = tip.x - nx * ARROW_LEN - px * ARROW_WING
    val ry = tip.y - ny * ARROW_LEN - py * ARROW_WING

    val edgeColor = theme.colors.edge.toHex()
    val mutedColor = theme.colors.edgeMuted.toHex()
    val bgColor = theme.colors.background.toHex()

    when (style) {
        ArrowStyle.OPEN -> {
            // Open chevron: two stroked lines meeting at the tip.
            builder.tag(
                "path",
                mapOf(
                    "d" to "M ${fa(lx)},${fa(ly)} L ${fa(tip.x)},${fa(tip.y)} L ${fa(rx)},${fa(ry)}",
                    "style" to "stroke:$edgeColor;stroke-width:1.5;fill:none;stroke-linejoin:round;",
                ),
            )
        }
        ArrowStyle.OPEN_MUTED -> {
            builder.tag(
                "path",
                mapOf(
                    "d" to "M ${fa(lx)},${fa(ly)} L ${fa(tip.x)},${fa(tip.y)} L ${fa(rx)},${fa(ry)}",
                    "style" to "stroke:$mutedColor;stroke-width:1.5;fill:none;stroke-linejoin:round;",
                ),
            )
        }
        ArrowStyle.TRIANGLE -> {
            // Hollow triangle: background fill to "erase" the line, border stroke.
            builder.tag(
                "polygon",
                mapOf(
                    "points" to "${fa(tip.x)},${fa(tip.y)} ${fa(lx)},${fa(ly)} ${fa(rx)},${fa(ry)}",
                    "style" to "stroke:$edgeColor;stroke-width:1.5;fill:$bgColor;stroke-linejoin:round;",
                ),
            )
        }
        ArrowStyle.TRIANGLE_MUTED -> {
            builder.tag(
                "polygon",
                mapOf(
                    "points" to "${fa(tip.x)},${fa(tip.y)} ${fa(lx)},${fa(ly)} ${fa(rx)},${fa(ry)}",
                    "style" to "stroke:$mutedColor;stroke-width:1.5;fill:$bgColor;stroke-linejoin:round;",
                ),
            )
        }
        ArrowStyle.FILLED -> {
            builder.tag(
                "polygon",
                mapOf(
                    "points" to "${fa(tip.x)},${fa(tip.y)} ${fa(lx)},${fa(ly)} ${fa(rx)},${fa(ry)}",
                    "style" to "stroke:$edgeColor;stroke-width:1;fill:$edgeColor;stroke-linejoin:round;",
                ),
            )
        }
        ArrowStyle.FILLED_MUTED -> {
            builder.tag(
                "polygon",
                mapOf(
                    "points" to "${fa(tip.x)},${fa(tip.y)} ${fa(lx)},${fa(ly)} ${fa(rx)},${fa(ry)}",
                    "style" to "stroke:$mutedColor;stroke-width:1;fill:$mutedColor;stroke-linejoin:round;",
                ),
            )
        }
    }
}

private fun fa(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
