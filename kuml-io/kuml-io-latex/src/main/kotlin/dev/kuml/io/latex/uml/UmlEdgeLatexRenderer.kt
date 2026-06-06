package dev.kuml.io.latex.uml

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.fmtCoord
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point

/**
 * Emits the TikZ source for a single edge route.
 *
 * The MVP supports all four [EdgeRoute] variants:
 *  - [EdgeRoute.Direct] → `\draw … -- (target);`
 *  - [EdgeRoute.OrthogonalRounded] / [EdgeRoute.TreeRounded]
 *    → `\draw … -- (wp1) -- (wp2) -- … -- (target);` with a `rounded
 *      corners=…` style applied at the picture level.
 *  - [EdgeRoute.Bezier] → `\draw … .. controls (c1) and (c2) .. (target);`
 *
 * Arrow heads come from the picked [Style] — pre-configured in the
 * picture-level `\tikzset{…}` block by [`KumlLatexRenderer`][dev.kuml.io.latex.KumlLatexRenderer].
 *
 * Coordinate flip: every Y is negated on emission so the layout's pixel-y-down
 * convention lines up with TikZ' y-up.
 */
internal object UmlEdgeLatexRenderer {
    enum class Style(
        internal val tikzStyle: String,
    ) {
        ASSOCIATION("kuml-association"),
        GENERALIZATION("kuml-generalization"),
        REALIZATION("kuml-realization"),
        DEPENDENCY("kuml-dependency"),
        PLAIN("kuml-edge-plain"),
    }

    fun render(
        route: EdgeRoute,
        style: Style,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val styleAttr = style.tikzStyle
        when (route) {
            is EdgeRoute.Direct -> renderDirect(route, styleAttr, options, out)
            is EdgeRoute.OrthogonalRounded ->
                renderPolyline(
                    route.source,
                    route.target,
                    route.waypoints,
                    route.cornerRadiusPx,
                    styleAttr,
                    options,
                    out,
                )
            is EdgeRoute.TreeRounded ->
                renderPolyline(
                    route.source,
                    route.target,
                    route.waypoints,
                    route.cornerRadiusPx,
                    styleAttr,
                    options,
                    out,
                )
            is EdgeRoute.Bezier -> renderBezier(route, styleAttr, options, out)
        }
    }

    private fun renderDirect(
        route: EdgeRoute.Direct,
        styleAttr: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        out.appendLine(
            "${options.indent}\\draw[$styleAttr] ${pt(route.source)} -- ${pt(route.target)};",
        )
    }

    private fun renderPolyline(
        source: Point,
        target: Point,
        waypoints: List<Point>,
        cornerRadiusPx: Float,
        styleAttr: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val cornerOpt = if (cornerRadiusPx > 0f) ", rounded corners=${fmtCoord(cornerRadiusPx)}pt" else ""
        val segments =
            buildString {
                append(pt(source))
                for (w in waypoints) {
                    append(" -- ")
                    append(pt(w))
                }
                append(" -- ")
                append(pt(target))
            }
        out.appendLine("${options.indent}\\draw[$styleAttr$cornerOpt] $segments;")
    }

    private fun renderBezier(
        route: EdgeRoute.Bezier,
        styleAttr: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        // TikZ Bézier syntax: `(a) .. controls (c1) and (c2) .. (b)` — needs
        // exactly two control points. Pad with the endpoint if the layout
        // engine gave us only one (cubic-as-quadratic-degenerate), or truncate
        // to the first two if it gave us more.
        val c1 = route.controlPoints.getOrNull(0) ?: route.source
        val c2 = route.controlPoints.getOrNull(1) ?: route.target
        out.appendLine(
            "${options.indent}\\draw[$styleAttr] ${pt(route.source)} .. controls " +
                "${pt(c1)} and ${pt(c2)} .. ${pt(route.target)};",
        )
    }

    /** Emit a TikZ coordinate from a layout [Point]. Negates Y for the axis flip. */
    private fun pt(p: Point): String = "(${fmtCoord(p.x)}pt, ${fmtCoord(-p.y)}pt)"
}
