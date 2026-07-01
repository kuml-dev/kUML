package dev.kuml.io.svg.sysml2.edge

import dev.kuml.io.svg.ArrowStyle
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.arrowDirection
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.renderInlineArrow
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import dev.kuml.sysml2.edge.Sysml2EdgeMetadata

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
     * Vertical shift per [labelStackIndex] step, downward.
     *
     * V11.x — parallel-edges fix. Two edges between the same pair of nodes
     * (e.g. `«derive»` + `«containment»` between two requirement boxes in a
     * trace diagram) have nearly identical label anchors and were colliding
     * into illegible glyph soup ("«de «containment»"). The caller now passes
     * a per-edge [labelStackIndex] derived from clustering label anchors; the
     * renderer offsets labels downward by `labelStackIndex * STACK_OFFSET_PX`
     * so each sibling occupies a distinct vertical band. 22 px is just over a
     * stereotype-line-plus-gap (12 px text + ~8 px padding), enough to keep
     * the per-label white background rects disjoint without ballooning the
     * stack height for the common single-sibling case.
     */
    private const val STACK_OFFSET_PX: Float = 22f

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
        labelStackIndex: Int = 0,
        overrideLabelAnchor: Pair<Float, Float>? = null,
    ) {
        // 1. Line + dash pattern.
        val (tag, attrs) = EdgePathBuilder.build(route)
        val lineAttrs =
            buildMap {
                putAll(attrs)
                put("class", "kuml-edge")
                metadata.dashArray?.let { put("stroke-dasharray", it) }
            }
        builder.tag(tag, lineAttrs)

        // Inline arrowhead (replaces marker-end url(#id) approach).
        val arrowStyle = toArrowStyle(metadata.arrowHead)
        if (arrowStyle != null) {
            val (arrowFrom, arrowTip) = route.arrowDirection()
            renderInlineArrow(arrowFrom, arrowTip, arrowStyle, theme, builder)
        }

        // 2. Labels (stereotype + plain label). Suppress emission entirely
        //    when both slots are null — most PAR bindings and bare ACT /
        //    STM transitions take this branch. Capture the nullable
        //    properties to locals so the compiler can flow-cast across the
        //    cross-module data-class boundary.
        val stereotype = metadata.stereotype
        val label = metadata.label
        if (stereotype == null && label == null) return
        // V3.x — Back-edge override: callers can supply an explicit anchor
        // (e.g. a point near the source state) to avoid the default
        // longest-segment midpoint landing inside an unrelated node or
        // outside the diagram frame.
        val (mx, my) = overrideLabelAnchor ?: labelAnchor(route)
        // V11.x — Parallel-Sibling-Versatz: Index 0 sitzt auf der Standard-
        // Position oberhalb der Linie; jeder weitere Sibling rutscht um eine
        // Bandbreite nach unten, damit die White-Background-Rects einander
        // nicht mehr überlappen.
        val topY = my - LABEL_BASELINE_OFFSET_PX + labelStackIndex * STACK_OFFSET_PX
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

    private fun toArrowStyle(head: Sysml2ArrowHead): ArrowStyle? =
        when (head) {
            Sysml2ArrowHead.None -> null
            Sysml2ArrowHead.OpenAngle -> ArrowStyle.OPEN
            // V2.0.45 — was incorrectly mapped to "arrow-open" (a stroke-only
            // chevron) which made directional flows on ACT and STM diagrams
            // render with nearly-invisible hairline tips. The dedicated
            // FILLED style (a solid polygon, edge-coloured fill) matches the
            // SysML 2 convention for ControlFlow / ObjectFlow arrow heads.
            Sysml2ArrowHead.FilledTriangle -> ArrowStyle.FILLED
            Sysml2ArrowHead.OpenTriangle -> ArrowStyle.TRIANGLE
        }

    /**
     * Maximum vertical distance for two labels to count as members of the same
     * parallel-edge band. Bindings between two horizontally-stacked boxes have
     * label-anchor Y values within a few pixels of each other; this slack
     * absorbs minor route-midpoint differences without falsely grouping labels
     * from unrelated edge clusters higher or lower in the diagram.
     */
    private const val VERTICAL_CLUSTER_PX: Float = 40f

    /**
     * Padding added to the horizontal-overlap test so adjacent label background
     * rectangles do not just kiss but keep a small visible gap. Matches the
     * `+ 6f` slack baked into [emitText]'s `approxW` plus a couple pixels for
     * stroke + anti-alias breathing room.
     */
    private const val HORIZONTAL_OVERLAP_PADDING_PX: Float = 6f

    private data class LabelCluster(
        var leftX: Float,
        var rightX: Float,
        val anchorY: Float,
        var count: Int,
    )

    /**
     * Estimate a label's half-width in SVG user units. Mirrors the formula
     * used by [emitText] for the white background rectangle
     * (`approxW = length * 6.2f + 6f`) so the clustering decision is in the
     * same coordinate system the renderer actually paints in.
     *
     * Null / blank labels report half-width 0 — they contribute no horizontal
     * overlap and therefore only cluster with vertically-overlapping
     * neighbours, preserving the V11.x behaviour for label-less edges.
     */
    private fun estimateLabelHalfWidth(labelText: String?): Float {
        if (labelText.isNullOrBlank()) return 0f
        return (labelText.length * 6.2f + HORIZONTAL_OVERLAP_PADDING_PX) / 2f
    }

    /**
     * Compute per-edge label stack indices so that parallel edges with
     * horizontally **overlapping** label rectangles get distinct vertical
     * bands instead of overwriting each other's labels.
     *
     * V2.x — Switched from Euclidean midpoint distance to bounding-box
     * overlap. The previous strategy missed the PAR-Newton case
     * (three bindings between a `«constraint»` and a `«part def»`, label
     * anchors 70–80 px apart horizontally but label rectangles up to 110 px
     * wide → rectangles overlap, midpoints don't). The bbox approach catches
     * both the original V11.x case (close midpoints, e.g. `«derive»` +
     * `«containment»` between two requirements, 30 px apart with combined
     * half-widths ~65 px) and the new wide-label case uniformly.
     *
     * Strategy: greedy bbox-overlap clustering. We walk the edges in their
     * natural map order (stable across runs because the caller passes a
     * `LinkedHashMap` from the layout result), and for each label:
     *
     *  1. Compute its `[anchorX - halfWidth, anchorX + halfWidth]` X range
     *     and its `anchorY`.
     *  2. Look for an existing cluster whose X range overlaps (with
     *     [HORIZONTAL_OVERLAP_PADDING_PX] slack) and whose Y is within
     *     [VERTICAL_CLUSTER_PX].
     *  3. If found, assign the next stack index and **expand** the cluster's
     *     X range to the union — so a third label that overlaps only with
     *     the second member still clusters correctly.
     *  4. If not found, start a new cluster at stack index 0.
     *
     * Ties are broken deterministically by insertion order — important
     * because the SVG sample-output diff would otherwise flap between runs.
     *
     * Callers should pre-filter the edge map to only include edges that will
     * actually go through [render] — feeding routes from a different renderer
     * (e.g. classic UML association via `EdgeRendererDispatcher`) is harmless
     * but wastes a few clustering ops.
     *
     * @param edges Triples of `(edgeId, route, labelText)`. `labelText` is the
     *   same string [render] will print on the edge (or `null` if the edge
     *   has no label). Callsites read it from the edge's
     *   [Sysml2EdgeMetadata.label] / [Sysml2EdgeMetadata.stereotype] pair —
     *   for a stereotype + label combination, pass the **wider** of the two
     *   so the clustering errs on the side of safety.
     */
    fun computeLabelStackIndices(edges: Iterable<Triple<EdgeId, EdgeRoute, String?>>): Map<EdgeId, Int> {
        val result = mutableMapOf<EdgeId, Int>()
        val clusters = mutableListOf<LabelCluster>()
        for ((edgeId, route, labelText) in edges) {
            val (mx, my) = labelAnchor(route)
            val halfWidth = estimateLabelHalfWidth(labelText)
            val leftX = mx - halfWidth
            val rightX = mx + halfWidth
            val matched =
                clusters.firstOrNull { c ->
                    val xOverlap =
                        leftX <= c.rightX + HORIZONTAL_OVERLAP_PADDING_PX &&
                            rightX >= c.leftX - HORIZONTAL_OVERLAP_PADDING_PX
                    val yClose = kotlin.math.abs(my - c.anchorY) < VERTICAL_CLUSTER_PX
                    xOverlap && yClose
                }
            if (matched == null) {
                clusters.add(LabelCluster(leftX = leftX, rightX = rightX, anchorY = my, count = 1))
                result[edgeId] = 0
            } else {
                result[edgeId] = matched.count
                matched.count += 1
                // Expand cluster X range to the union so subsequent labels
                // that overlap only with the new member still cluster.
                matched.leftX = kotlin.math.min(matched.leftX, leftX)
                matched.rightX = kotlin.math.max(matched.rightX, rightX)
            }
        }
        return result
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

    private fun fmt(v: Float): String = fmt2(v)
}
