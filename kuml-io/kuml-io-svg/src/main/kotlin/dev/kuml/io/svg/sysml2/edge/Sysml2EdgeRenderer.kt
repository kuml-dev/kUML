package dev.kuml.io.svg.sysml2.edge

import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import dev.kuml.sysml2.edge.Sysml2EdgeMetadata
import java.util.Locale

/**
 * Renders a single SysML 2 edge — line + optional dash pattern, arrow head
 * and stereotype / plain label — into the `<g id="edges">` SVG group.
 *
 * V2.0.13 dispatches every UC / REQ / STM / ACT / PAR edge through this
 * single path. The metadata is produced by a
 * `dev.kuml.sysml2.edge.Sysml2EdgeAdapter`; route geometry comes from the
 * already-shifted [EdgeRoute] (padding has been applied by the caller).
 *
 *  - Line: built via [EdgePathBuilder.build]; the `stroke-dasharray`
 *    attribute is added when [Sysml2EdgeMetadata.dashArray] is non-null,
 *    otherwise the line renders solid.
 *  - Arrow head: picked from a small `<marker>` palette declared inline
 *    via the `marker-end` attribute. The existing `arrow-open` /
 *    `arrow-triangle` markers from `SvgDocument.buildMarkers` are reused
 *    where they fit; [Sysml2ArrowHead.None] simply omits `marker-end`.
 *  - Labels: positioned at the midpoint of the longest segment for
 *    orthogonal / tree routes, at the source/target midpoint for direct
 *    and Bezier routes (true Bezier-midpoint computation is V2.x polish).
 *    Two labels stack vertically: stereotype on top (kuml-stereotype CSS
 *    class), plain label below (kuml-body CSS class).
 */
internal object Sysml2EdgeRenderer {
    /** Vertical gap between stereotype line and plain label line. */
    private const val LABEL_LINE_HEIGHT_PX: Float = 12f

    /**
     * Vertical offset *above* the line where labels start. Picked so the
     * stereotype baseline sits clear of the line without colliding with the
     * arrow head when the line is short.
     */
    private const val LABEL_BASELINE_OFFSET_PX: Float = 4f

    /**
     * Draw a SysML 2 edge.
     *
     * @param route Already-padded route — caller has shifted it through
     *   `shiftRoute(…)` in the SVG renderer.
     * @param metadata Metadata produced by the matching adapter.
     * @param theme Theme — accessed for future stylistic tuning (currently
     *   only used implicitly via CSS classes already declared in
     *   `SvgDocument.buildDefs`). Kept in the signature so future polish
     *   waves can add theme-aware shape choices without an API break.
     * @param builder Edges-group [SvgBuilder].
     */
    fun render(
        route: EdgeRoute,
        metadata: Sysml2EdgeMetadata,
        theme: KumlTheme,
        builder: SvgBuilder,
    ) {
        // 1. Line + dash pattern.
        val (tag, attrs) = EdgePathBuilder.build(route)
        val lineAttrs =
            buildMap {
                putAll(attrs)
                put("class", "kuml-edge")
                metadata.dashArray?.let { put("stroke-dasharray", it) }
                arrowMarkerId(metadata.arrowHead)?.let { put("marker-end", "url(#$it)") }
            }
        builder.tag(tag, lineAttrs)

        // 2. Labels (stereotype + plain label). Suppress emission entirely
        //    when both slots are null — most PAR bindings and bare ACT /
        //    STM transitions take this branch. Capture the nullable
        //    properties to locals so the compiler can flow-cast across the
        //    cross-module data-class boundary.
        val stereotype = metadata.stereotype
        val label = metadata.label
        if (stereotype == null && label == null) return
        val (mx, my) = labelAnchor(route)
        val topY = my - LABEL_BASELINE_OFFSET_PX
        if (stereotype != null && label != null) {
            emitText(builder, stereotype, mx, topY - LABEL_LINE_HEIGHT_PX, "kuml-stereotype")
            emitText(builder, label, mx, topY, "kuml-body")
        } else if (stereotype != null) {
            emitText(builder, stereotype, mx, topY, "kuml-stereotype")
        } else if (label != null) {
            emitText(builder, label, mx, topY, "kuml-body")
        }

        // V2.x: true Bezier midpoint via de-Casteljau, multi-line labels
        // with wrapping, endpoint markers (open square on ObjectFlow),
        // collision avoidance for overlapping labels.
        @Suppress("UNUSED_EXPRESSION")
        theme
    }

    private fun emitText(
        builder: SvgBuilder,
        content: String,
        x: Float,
        y: Float,
        cssClass: String,
    ) {
        // Background rect for readability when labels overlap
        val approxW = content.length * 6.2f + 6f
        val approxH = 12f
        builder.tag(
            "rect",
            mapOf(
                "x" to fmt(x - approxW / 2f),
                "y" to fmt(y - approxH + 2f),
                "width" to fmt(approxW),
                "height" to fmt(approxH),
                "fill" to "white",
                "stroke" to "none",
            ),
        )
        builder.tag(
            "text",
            mapOf(
                "class" to cssClass,
                "x" to fmt(x),
                "y" to fmt(y),
                "text-anchor" to "middle",
            ),
        ) {
            text(content)
        }
    }

    private fun arrowMarkerId(head: Sysml2ArrowHead): String? =
        when (head) {
            Sysml2ArrowHead.None -> null
            Sysml2ArrowHead.OpenAngle -> "arrow-open"
            Sysml2ArrowHead.FilledTriangle -> "arrow-open"
            Sysml2ArrowHead.OpenTriangle -> "arrow-triangle"
        }

    /**
     * Compute the (x, y) anchor for a label above the line.
     *
     * Strategy per route kind:
     *  - [EdgeRoute.Direct] → midpoint of the source/target segment.
     *  - [EdgeRoute.OrthogonalRounded] / [EdgeRoute.TreeRounded] → midpoint
     *    of the longest segment in `[source] + waypoints + [target]`.
     *  - [EdgeRoute.Bezier] → midpoint of the source/target straight line.
     *    True Bezier midpoint (de-Casteljau) is V2.x polish.
     */
    fun labelAnchor(route: EdgeRoute): Pair<Float, Float> =
        when (route) {
            is EdgeRoute.Direct -> midpoint(route.source, route.target)
            is EdgeRoute.OrthogonalRounded ->
                longestSegmentMidpoint(listOf(route.source) + route.waypoints + listOf(route.target))
            is EdgeRoute.TreeRounded ->
                longestSegmentMidpoint(listOf(route.source) + route.waypoints + listOf(route.target))
            is EdgeRoute.Bezier -> midpoint(route.source, route.target)
        }

    private fun midpoint(
        a: Point,
        b: Point,
    ): Pair<Float, Float> = ((a.x + b.x) / 2f) to ((a.y + b.y) / 2f)

    private fun longestSegmentMidpoint(points: List<Point>): Pair<Float, Float> {
        if (points.size < 2) return points.firstOrNull()?.let { it.x to it.y } ?: (0f to 0f)
        var bestIdx = 0
        var bestLen2 = 0f
        for (i in 0 until points.size - 1) {
            val dx = points[i + 1].x - points[i].x
            val dy = points[i + 1].y - points[i].y
            val len2 = dx * dx + dy * dy
            if (len2 > bestLen2) {
                bestLen2 = len2
                bestIdx = i
            }
        }
        return midpoint(points[bestIdx], points[bestIdx + 1])
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(Locale.ROOT, v)
    }
}
