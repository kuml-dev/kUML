package dev.kuml.io.svg.sysml2.edge

import dev.kuml.io.svg.ArrowStyle
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.arrowDirection
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.renderInlineArrow
import dev.kuml.io.svg.sysml2.wrapWords
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
     * Max characters per wrapped edge-label line. Long trigger/guard/action
     * text (e.g. `"endRoom [moderator or global BOARD/ADMIN]"`) previously
     * rendered as a single unclamped line — the white background rect grew
     * exactly as wide as the raw text, which could run past the diagram
     * frame or over neighbouring nodes since nothing upstream reserves
     * horizontal room for edge labels (see [emitText]). Word-wrapping at a
     * fixed character budget keeps every label a bounded, readable width
     * instead of silently overflowing. 40 chars is wide enough that most
     * short stereotypes/guards stay on one line, matching the box-width
     * feel of the shortest content-aware node boxes.
     */
    private const val EDGE_LABEL_WRAP_CHARS: Int = 40

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
        builder.tag(name = tag, attrs = lineAttrs)

        // Inline arrowhead (replaces marker-end url(#id) approach).
        val arrowStyle = toArrowStyle(metadata.arrowHead)
        if (arrowStyle != null) {
            val (arrowFrom, arrowTip) = route.arrowDirection()
            renderInlineArrow(from = arrowFrom, tip = arrowTip, style = arrowStyle, theme = theme, builder = builder)
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
            emitText(builder = builder, content = stereotype, x = mx, y = topY - LABEL_LINE_HEIGHT_PX, cssClass = "kuml-stereotype")
            emitText(builder = builder, content = label, x = mx, y = topY, cssClass = "kuml-body")
        } else if (stereotype != null) {
            emitText(builder = builder, content = stereotype, x = mx, y = topY, cssClass = "kuml-stereotype")
        } else if (label != null) {
            emitText(builder = builder, content = label, x = mx, y = topY, cssClass = "kuml-body")
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
        // Word-wrap long labels instead of drawing one unbounded-width line —
        // see EDGE_LABEL_WRAP_CHARS. `y` is the baseline of the FIRST line;
        // wrapped lines stack downward, away from the line/stereotype above.
        val lines = wrapWords(text = content, maxChars = EDGE_LABEL_WRAP_CHARS).ifEmpty { listOf(content) }
        val singleLineH = 12f
        val approxW = (lines.maxOf { it.length }) * 6.2f + 6f
        val approxH = singleLineH + (lines.size - 1) * LABEL_LINE_HEIGHT_PX
        // Background rect for readability when labels overlap
        builder.tag(
            name = "rect",
            attrs =
                mapOf(
                    "x" to fmt(x - approxW / 2f),
                    "y" to fmt(y - singleLineH + 2f),
                    "width" to fmt(approxW),
                    "height" to fmt(approxH),
                    "fill" to "white",
                    "stroke" to "none",
                ),
        )
        lines.forEachIndexed { index, line ->
            builder.tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to cssClass,
                        "x" to fmt(x),
                        "y" to fmt(y + index * LABEL_LINE_HEIGHT_PX),
                        "text-anchor" to "middle",
                    ),
            ) {
                text(line)
            }
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
     * used by [emitText] for the white background rectangle — same
     * [EDGE_LABEL_WRAP_CHARS] word-wrap, half-width taken from the *widest
     * wrapped line* (`approxW = widestLine.length * 6.2f + 6f`) — so the
     * clustering decision is in the same coordinate system the renderer
     * actually paints in, including for labels long enough to wrap.
     *
     * Null / blank labels report half-width 0 — they contribute no horizontal
     * overlap and therefore only cluster with vertically-overlapping
     * neighbours, preserving the V11.x behaviour for label-less edges.
     *
     * Not private — [dev.kuml.io.svg.KumlSvgRenderer.umlStmWidenForLabelOverhang]
     * reuses it to size how much wider an STM frame must grow so a label's
     * estimated background rect fits inside the frame's own border, in the
     * same coordinate system as the clustering decision above.
     */
    internal fun estimateLabelHalfWidth(labelText: String?): Float {
        if (labelText.isNullOrBlank()) return 0f
        val widestLine = wrapWords(text = labelText, maxChars = EDGE_LABEL_WRAP_CHARS).maxOfOrNull { it.length } ?: labelText.length
        return (widestLine * 6.2f + HORIZONTAL_OVERLAP_PADDING_PX) / 2f
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
    fun computeLabelStackIndices(edges: Iterable<Triple<EdgeId, EdgeRoute, String?>>): Map<EdgeId, Int> =
        computeLabelStackAssignments(edges).mapValues { it.value.index }

    /**
     * Per-edge outcome of [computeLabelStackAssignments]: the vertical band
     * index within its cluster, plus — for every sibling *after* the first —
     * the anchor to render at instead of the edge's own natural
     * [labelAnchor].
     *
     * @property index 0 for the first member of a cluster (and for any
     *   unclustered label), incrementing per sibling.
     * @property anchorOverride `null` for index 0 (renders at its own
     *   natural anchor, unchanged from pre-clustering behaviour). For
     *   index > 0, the (x, y) to pass as `overrideLabelAnchor` to [render] —
     *   see [computeLabelStackAssignments] KDoc for why this is required.
     */
    data class LabelStackAssignment(
        val index: Int,
        val anchorOverride: Pair<Float, Float>?,
    )

    /**
     * Like [computeLabelStackIndices], but additionally resolves the anchor
     * each clustered sibling must render at.
     *
     * Bug fix: [render] applies a sibling's `labelStackIndex * STACK_OFFSET_PX`
     * offset on top of *that edge's own* [labelAnchor] `my`. That only
     * produces the intended [STACK_OFFSET_PX]-sized gap between two siblings
     * when both share (approximately) the same natural `my` — true for the
     * original parallel-edges case this mechanism was built for (near-
     * identical routes between the same two nodes). It breaks down once
     * siblings have differently-shaped routes that land at different natural
     * Y — e.g. one transition routed as a straight `Direct` line and another
     * as a multi-segment orthogonal route converging on the same final
     * state. Their natural `my` values can differ by more than the intended
     * gap, so adding the *same* offset to two *different* bases can leave
     * consecutive labels only a few pixels apart (or, worse, overlapping)
     * instead of a full [STACK_OFFSET_PX] apart.
     *
     * Fix: every sibling after the cluster's first member renders at a
     * shared baseline Y — the first member's own natural `my` (already
     * tracked as [LabelCluster.anchorY], unmodified after the cluster is
     * seeded) — instead of its own natural `my`. The first member keeps
     * rendering at its untouched natural anchor (`anchorOverride = null`,
     * byte-for-byte identical to pre-fix output), and every later sibling
     * gets an explicit `(ownMx, clusterBaseline)` override so
     * `labelStackIndex * STACK_OFFSET_PX` measures a consistent gap from
     * that shared baseline instead of compounding onto a different one.
     */
    fun computeLabelStackAssignments(edges: Iterable<Triple<EdgeId, EdgeRoute, String?>>): Map<EdgeId, LabelStackAssignment> =
        computeLabelStackAssignmentsFromAnchors(
            edges.map { (edgeId, route, labelText) ->
                LabelAnchorInput(edgeId = edgeId, anchor = labelAnchor(route), labelText = labelText)
            },
        )

    /**
     * One edge's input to [computeLabelStackAssignmentsFromAnchors]: its id,
     * the anchor its label would render at absent any clustering override,
     * and its label text (for half-width estimation).
     */
    data class LabelAnchorInput(
        val edgeId: EdgeId,
        val anchor: Pair<Float, Float>,
        val labelText: String?,
    )

    /**
     * Same clustering algorithm as [computeLabelStackAssignments], but takes
     * each edge's label anchor directly instead of deriving it from
     * [labelAnchor]. Lets a caller substitute a different anchor for edges
     * whose *actual* rendered position diverges from their route's natural
     * longest-segment midpoint — e.g. UML state-machine back-edges, which
     * render at a fixed 8%-of-arc-length point near the source instead of
     * their natural midpoint (see [dev.kuml.io.svg.KumlSvgRenderer]'s STM
     * back-edge handling). Feeding the *natural* midpoint into clustering for
     * those edges misses overlaps entirely: several back-edges converging on
     * the same target state can have natural midpoints far apart (different
     * long vertical runs) even though their real, overridden anchors land
     * within a few px of each other — exactly the bug this overload exists to
     * let callers avoid.
     */
    fun computeLabelStackAssignmentsFromAnchors(entries: Iterable<LabelAnchorInput>): Map<EdgeId, LabelStackAssignment> {
        val result = mutableMapOf<EdgeId, LabelStackAssignment>()
        val clusters = mutableListOf<LabelCluster>()
        for ((edgeId, anchor, labelText) in entries) {
            val (mx, my) = anchor
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
                result[edgeId] = LabelStackAssignment(index = 0, anchorOverride = null)
            } else {
                result[edgeId] = LabelStackAssignment(index = matched.count, anchorOverride = mx to matched.anchorY)
                matched.count += 1
                // Expand cluster X range to the union so subsequent labels
                // that overlap only with the new member still cluster.
                // anchorY is deliberately NOT updated — it stays the shared
                // baseline every later sibling renders relative to.
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
            is EdgeRoute.Direct -> midpoint(a = route.source, b = route.target)
            is EdgeRoute.OrthogonalRounded ->
                longestSegmentMidpoint(listOf(route.source) + route.waypoints + listOf(route.target))
            is EdgeRoute.TreeRounded ->
                longestSegmentMidpoint(listOf(route.source) + route.waypoints + listOf(route.target))
            is EdgeRoute.Bezier -> midpoint(a = route.source, b = route.target)
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
        return midpoint(a = points[bestIdx], b = points[bestIdx + 1])
    }

    private fun fmt(v: Float): String = fmt2(v)
}
