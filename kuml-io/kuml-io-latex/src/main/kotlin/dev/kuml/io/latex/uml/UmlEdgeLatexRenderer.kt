package dev.kuml.io.latex.uml

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.fmtCoord
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.UmlNavigability

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
        BIDIRECTIONAL("kuml-edge-bidi"),

        /** Anchor line of a note; the style is defined in the note `\tikzset` block only. */
        NOTE_ANCHOR("kuml-note-anchor"),
    }

    /** Length in pt of the diamond tips. */
    internal const val DIAMOND_LENGTH_PT = 16

    /** Length in pt of the open arrowhead. */
    internal const val OPEN_HEAD_LENGTH_PT = 12

    /**
     * Length in pt of the stealth tip of style `kuml-association` (2.2 mm). It has no effect on label
     * placement, because the minimum label offset is larger; it is kept so the table of ends is complete.
     */
    internal const val STEALTH_LENGTH_PT = 2.2f * 72.27f / 25.4f

    /** Open (hollow) diamond for shared aggregation, drawn at the source end. */
    internal const val DIAMOND_OPEN = "Diamond[open, length=${DIAMOND_LENGTH_PT}pt, width=10pt]"

    /** Filled diamond for composition, drawn at the source end. */
    internal const val DIAMOND_FILLED = "Diamond[length=${DIAMOND_LENGTH_PT}pt, width=10pt]"

    /** Open arrowhead (chevron) marking the single navigable end. */
    internal const val OPEN_HEAD = "Straight Barb[length=${OPEN_HEAD_LENGTH_PT}pt, width=10pt]"

    /** What is drawn at one end of an association line, with the length it occupies along the line. */
    internal enum class UmlLineEnd(
        val lengthPt: Float,
    ) {
        NONE(0f),
        STEALTH(STEALTH_LENGTH_PT),
        OPEN_HEAD(OPEN_HEAD_LENGTH_PT.toFloat()),
        DIAMOND(DIAMOND_LENGTH_PT.toFloat()),

        /** A diamond followed by an open head, both at the source end (`{diamond OPEN}-`). */
        DIAMOND_AND_OPEN_HEAD((DIAMOND_LENGTH_PT + OPEN_HEAD_LENGTH_PT).toFloat()),
    }

    /**
     * The tips at (source, target) for an association; mirrors [edgeEndsOverride] and is kept
     * consistent with it by a test.
     */
    fun lineEnds(
        aggregation: AggregationKind,
        navigability: UmlNavigability,
    ): Pair<UmlLineEnd, UmlLineEnd> {
        val diamond = aggregation != AggregationKind.NONE
        return when (navigability) {
            UmlNavigability.BOTH, UmlNavigability.NEITHER ->
                if (diamond) UmlLineEnd.DIAMOND to UmlLineEnd.NONE else UmlLineEnd.NONE to UmlLineEnd.STEALTH
            UmlNavigability.TARGET_ONLY ->
                (if (diamond) UmlLineEnd.DIAMOND else UmlLineEnd.NONE) to UmlLineEnd.OPEN_HEAD
            UmlNavigability.SOURCE_ONLY ->
                (if (diamond) UmlLineEnd.DIAMOND_AND_OPEN_HEAD else UmlLineEnd.OPEN_HEAD) to UmlLineEnd.NONE
        }
    }

    /**
     * Arrow-option override for an association, or `null` to keep the style's default
     * (the plain association, [AggregationKind.NONE] with both or neither end navigable,
     * stays byte-identical to the output before the diamonds were drawn).
     *
     * Diamonds sit at the source end (the aggregate or composite), matching the SVG renderer;
     * the open arrowhead sits at the one navigable end.
     */
    fun edgeEndsOverride(
        aggregation: AggregationKind,
        navigability: UmlNavigability,
    ): String? {
        val diamond =
            when (aggregation) {
                AggregationKind.NONE -> null
                AggregationKind.SHARED -> DIAMOND_OPEN
                AggregationKind.COMPOSITE -> DIAMOND_FILLED
            }
        return when (navigability) {
            UmlNavigability.BOTH, UmlNavigability.NEITHER -> diamond?.let { "{$it}-" }
            UmlNavigability.TARGET_ONLY -> if (diamond == null) "-{$OPEN_HEAD}" else "{$diamond}-{$OPEN_HEAD}"
            UmlNavigability.SOURCE_ONLY -> if (diamond == null) "{$OPEN_HEAD}-" else "{$diamond $OPEN_HEAD}-"
        }
    }

    fun render(
        route: EdgeRoute,
        style: Style,
        options: LatexRenderOptions,
        out: StringBuilder,
        arrowOverride: String? = null,
    ) {
        val styleAttr = if (arrowOverride == null) style.tikzStyle else "${style.tikzStyle}, $arrowOverride"
        when (route) {
            is EdgeRoute.Direct -> renderDirect(route = route, styleAttr = styleAttr, options = options, out = out)
            is EdgeRoute.OrthogonalRounded ->
                renderPolyline(
                    source = route.source,
                    target = route.target,
                    waypoints = route.waypoints,
                    cornerRadiusPx = route.cornerRadiusPx,
                    styleAttr = styleAttr,
                    options = options,
                    out = out,
                )
            is EdgeRoute.TreeRounded ->
                renderPolyline(
                    source = route.source,
                    target = route.target,
                    waypoints = route.waypoints,
                    cornerRadiusPx = route.cornerRadiusPx,
                    styleAttr = styleAttr,
                    options = options,
                    out = out,
                )
            is EdgeRoute.Bezier -> renderBezier(route = route, styleAttr = styleAttr, options = options, out = out)
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
