package dev.kuml.io.svg.sysml2.edge

import dev.kuml.io.svg.ArrowStyle
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.arrowDirection
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.renderInlineArrow
import dev.kuml.io.svg.sysml2.wrapWords
import dev.kuml.io.svg.xmlEscapeAttr
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
    private const val STM_LABEL_PAD: Float = 3f

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

    /** Wave C2 — maximum distance of an edge label from its own line (before stack offsets), px. */
    internal const val LABEL_LEASH_PX: Float = 16f

    internal fun polylinePoints(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }

    /** Distance of (x, y) from the polyline of [route]. */
    internal fun distanceToPolyline(
        x: Float,
        y: Float,
        route: EdgeRoute,
    ): Float {
        val pts = polylinePoints(route)
        var best = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
            val px = a.x + t * dx
            val py = a.y + t * dy
            val d = kotlin.math.sqrt((x - px) * (x - px) + (y - py) * (y - py))
            if (d < best) best = d
        }
        return if (best == Float.MAX_VALUE) 0f else best
    }

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
     * @param id Stable element id for this edge — wrapped as `<g id="…">`
     *   around the rendered fragment (fix/edge-svg-element-ids) so downstream
     *   consumers (e.g. kUML Portal's click-to-select) can address the edge
     *   in the DOM. Callers pass the synthetic [dev.kuml.layout.EdgeId] value
     *   the layout result assigned to this edge.
     */
    fun render(
        id: String,
        route: EdgeRoute,
        metadata: Sysml2EdgeMetadata,
        theme: KumlTheme,
        builder: SvgBuilder,
        labelStackIndex: Int = 0,
        overrideLabelAnchor: Pair<Float, Float>? = null,
        backdropFill: String? = null,
        suppressLabels: Boolean = false,
        leashLabels: Boolean = false,
        plannedLabel: dev.kuml.layout.bridge.label.PlacedLabel? = null,
        interruptRegion: dev.kuml.layout.Rect? = null,
    ) {
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(id))) {
            renderContent(
                route = route,
                metadata = metadata,
                theme = theme,
                builder = this,
                labelStackIndex = labelStackIndex,
                overrideLabelAnchor = overrideLabelAnchor,
                backdropFill = backdropFill,
                suppressLabels = suppressLabels,
                leashLabels = leashLabels,
                plannedLabel = plannedLabel,
                interruptRegion = interruptRegion,
            )
        }
    }

    /**
     * Wave 2F: where the interruption glyph of [route] sits and which way the line runs there: the first crossing of the
     * frame of the source's region ([region]), else the middle of the longest segment. `null` for a degenerate route.
     */
    internal fun interruptGlyphAnchor(
        route: EdgeRoute,
        region: dev.kuml.layout.Rect?,
    ): Pair<Point, Point>? {
        val pts = polylinePoints(route)
        if (pts.size < 2) return null
        if (region != null) {
            val l = region.origin.x
            val t = region.origin.y
            val r = l + region.size.width
            val b = t + region.size.height

            fun inside(p: Point) = p.x > l && p.x < r && p.y > t && p.y < b
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val c = pts[i + 1]
                if (!inside(a) || inside(c)) continue
                // axis parallel segment leaving the frame: walk to the boundary
                val dx = c.x - a.x
                val dy = c.y - a.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                if (len < 1e-3f) continue
                var best = 1f
                if (dx > 1e-3f) best = minOf(best, (r - a.x) / dx)
                if (dx < -1e-3f) best = minOf(best, (l - a.x) / dx)
                if (dy > 1e-3f) best = minOf(best, (b - a.y) / dy)
                if (dy < -1e-3f) best = minOf(best, (t - a.y) / dy)
                return Point(x = a.x + dx * best, y = a.y + dy * best) to Point(x = dx / len, y = dy / len)
            }
        }
        var bestIdx = 0
        var bestLen = 0f
        for (i in 0 until pts.size - 1) {
            val dx = pts[i + 1].x - pts[i].x
            val dy = pts[i + 1].y - pts[i].y
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len > bestLen) {
                bestLen = len
                bestIdx = i
            }
        }
        if (bestLen < 1e-3f) return null
        val a = pts[bestIdx]
        val c = pts[bestIdx + 1]
        return Point(x = (a.x + c.x) / 2f, y = (a.y + c.y) / 2f) to Point(x = (c.x - a.x) / bestLen, y = (c.y - a.y) / bestLen)
    }

    /** Bounding box of the glyph at [at], padded by 2 px, as an obstacle for label planning. */
    internal fun glyphRect(
        at: Point,
        dir: Point,
    ): dev.kuml.layout.Rect {
        val pts = interruptGlyph(at = at, dir = dir)
        val x0 = pts.minOf { it.x } - 2f
        val y0 = pts.minOf { it.y } - 2f
        return dev.kuml.layout.Rect(
            origin = Point(x = x0, y = y0),
            size =
                dev.kuml.layout.Size(
                    width = pts.maxOf { it.x } + 2f - x0,
                    height =
                        pts.maxOf { it.y } + 2f - y0,
                ),
        )
    }

    /** Wave 2F: the lightning-bolt glyph (12 x 16 px) centred on [at], its long axis along [dir]. */
    internal fun interruptGlyph(
        at: Point,
        dir: Point,
    ): List<Point> {
        // (across, along) coordinates of the bolt
        val shape =
            listOf(
                1.5f to -8f,
                -5f to 1f,
                -0.5f to 1f,
                -1.5f to 8f,
                5f to -2f,
                0.5f to -2f,
            )
        val nx = -dir.y
        val ny = dir.x
        return shape.map { (a, u) -> Point(x = at.x + nx * a + dir.x * u, y = at.y + ny * a + dir.y * u) }
    }

    private fun renderContent(
        route: EdgeRoute,
        metadata: Sysml2EdgeMetadata,
        theme: KumlTheme,
        builder: SvgBuilder,
        labelStackIndex: Int = 0,
        overrideLabelAnchor: Pair<Float, Float>? = null,
        backdropFill: String? = null,
        suppressLabels: Boolean = false,
        leashLabels: Boolean = false,
        plannedLabel: dev.kuml.layout.bridge.label.PlacedLabel? = null,
        interruptRegion: dev.kuml.layout.Rect? = null,
    ) {
        // 1. Line + dash pattern.
        val (tag, attrs) = EdgePathBuilder.build(route)
        val lineAttrs =
            buildMap {
                putAll(attrs)
                put("class", "kuml-edge")
                metadata.dashArray?.let { put("stroke-dasharray", it) }
                // Inline style: the `.kuml-edge` class rule would win over a presentation attribute.
                val inline = listOfNotNull(metadata.strokeColor?.let { "stroke:$it" }, metadata.lineCap?.let { "stroke-linecap:$it" })
                if (inline.isNotEmpty()) put("style", inline.joinToString(";"))
            }
        builder.tag(name = tag, attrs = lineAttrs)
        // Wave 2F: an interrupting flow carries a lightning-bolt glyph on a straight line (no zigzag path).
        if (metadata.lightning) {
            interruptGlyphAnchor(route = route, region = interruptRegion)?.let { (at, dir) ->
                builder.tag(
                    name = "polygon",
                    attrs =
                        mapOf(
                            "points" to interruptGlyph(at = at, dir = dir).joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" },
                            "class" to "kuml-interrupt-glyph",
                        ),
                )
            }
        }

        // Inline arrowhead (replaces marker-end url(#id) approach).
        val arrowStyle = toArrowStyle(metadata.arrowHead)
        if (arrowStyle != null) {
            if (metadata.headAtSource) {
                // Composition / aggregation: the diamond sits on the owning (source) end.
                val (srcNext, srcTip) = route.sourceArrowDirection()
                renderInlineArrow(
                    from = srcNext,
                    tip = srcTip,
                    style = arrowStyle,
                    theme = theme,
                    builder = builder,
                    strokeColor = metadata.strokeColor,
                )
            } else {
                val (arrowFrom, arrowTip) = route.arrowDirection()
                renderInlineArrow(
                    from = arrowFrom,
                    tip = arrowTip,
                    style = arrowStyle,
                    theme = theme,
                    builder = builder,
                    strokeColor = metadata.strokeColor,
                )
            }
        }
        metadata.endLabel?.let { emitEndLabel(route = route, content = it, builder = builder) }
        // With a planned label the item-flow texts are part of that label block; without a plan they sit at the arrows.
        if (metadata.midArrows.isNotEmpty()) {
            emitMidArrows(route = route, arrows = metadata.midArrows, builder = builder, drawItemLabels = plannedLabel == null)
        }

        // STM labels are emitted later by emitStmLabels, after all edges, so no later edge paints over them.
        if (suppressLabels) return

        // PAL 2B-c: a label planned by Sysml2EdgeLabelPlan is drawn exactly at its planned rectangle.
        if (plannedLabel != null) {
            val lines = drawnLabelLines(metadata)
            if (lines.isNotEmpty()) {
                emitPlannedLabel(placed = plannedLabel, lines = lines, backdrop = backdropFill ?: "white", builder = builder)
            }
            return
        }

        // 2. Labels (stereotype + plain label). Suppress emission entirely
        //    when both slots are null — most PAR bindings and bare ACT /
        //    STM transitions take this branch. Capture the nullable
        //    properties to locals so the compiler can flow-cast across the
        //    cross-module data-class boundary.
        val stereotype = metadata.stereotype
        val label = metadata.label
        // Legacy diagrams keep the white label box; the IBD passes the theme background instead.
        val backdrop = backdropFill ?: "white"
        if (stereotype == null && label == null) return
        // V3.x — Back-edge override: callers can supply an explicit anchor
        // (e.g. a point near the source state) to avoid the default
        // longest-segment midpoint landing inside an unrelated node or
        // outside the diagram frame.
        val (mx0, my0) =
            overrideLabelAnchor
                ?: if (metadata.midArrows.isNotEmpty()) besideAnchor(route = route, labelText = label) else labelAnchor(route)
        // Wave C2 leash: a label may sit at most LABEL_LEASH_PX (plus its stack offset) away from its own
        // line; if cluster stacking or an override anchor drifted it further, fall back to the line's
        // own longest-segment midpoint.
        val (mx, my) =
            if (leashLabels &&
                distanceToPolyline(x = mx0, y = my0 - LABEL_BASELINE_OFFSET_PX + labelStackIndex * STACK_OFFSET_PX, route = route) >
                LABEL_LEASH_PX + labelStackIndex * STACK_OFFSET_PX
            ) {
                labelAnchor(route)
            } else {
                mx0 to my0
            }
        // V11.x — Parallel-Sibling-Versatz: Index 0 sitzt auf der Standard-
        // Position oberhalb der Linie; jeder weitere Sibling rutscht um eine
        // Bandbreite nach unten, damit die White-Background-Rects einander
        // nicht mehr überlappen.
        val topY = my - LABEL_BASELINE_OFFSET_PX + labelStackIndex * STACK_OFFSET_PX
        if (stereotype != null && label != null) {
            emitText(
                builder = builder,
                content = stereotype,
                x = mx,
                y = topY - LABEL_LINE_HEIGHT_PX,
                cssClass = "kuml-stereotype",
                backdrop = backdrop,
            )
            emitText(builder = builder, content = label, x = mx, y = topY, cssClass = "kuml-body", backdrop = backdrop)
        } else if (stereotype != null) {
            emitText(builder = builder, content = stereotype, x = mx, y = topY, cssClass = "kuml-stereotype", backdrop = backdrop)
        } else if (label != null) {
            emitText(builder = builder, content = label, x = mx, y = topY, cssClass = "kuml-body", backdrop = backdrop)
        }

        // V2.x: true Bezier midpoint via de-Casteljau, multi-line labels
        // with wrapping, endpoint markers (open square on ObjectFlow),
        // collision avoidance for overlapping labels.
        @Suppress("UNUSED_EXPRESSION")
        theme
    }

    /** One drawn line of an edge label: its text and CSS class. */
    internal data class DrawnLabelLine(
        val text: String,
        val css: String,
    )

    /**
     * The lines an edge label of [metadata] is drawn in (stereotype first, then the label, then the item-flow labels,
     * each word-wrapped): the
     * single source for the size the label plan reserves and the text [emitPlannedLabel] draws.
     */
    internal fun drawnLabelLines(metadata: Sysml2EdgeMetadata): List<DrawnLabelLine> {
        val out = ArrayList<DrawnLabelLine>()
        val wrapChars = metadata.labelWrapChars ?: EDGE_LABEL_WRAP_CHARS
        metadata.stereotype?.let { st ->
            wrapWords(text = st, maxChars = wrapChars).ifEmpty { listOf(st) }.forEach {
                out +=
                    DrawnLabelLine(text = it, css = "kuml-stereotype")
            }
        }
        metadata.label?.let { l ->
            wrapWords(text = l, maxChars = wrapChars).ifEmpty { listOf(l) }.forEach {
                // Wave 2F: the «Type» of an object flow is secondary text, upright.
                out +=
                    DrawnLabelLine(text = it, css = if (l.startsWith("«")) "kuml-object-type" else "kuml-body")
            }
        }
        // Nacharbeit 48 (N-IBD-F g): the item-flow labels follow the connection label in the same block, one line per
        // mid-line arrow in declaration order, so the plan reserves one rectangle for both and they cannot overlap.
        metadata.midArrows.forEach { arrow ->
            wrapWords(text = arrow.label, maxChars = wrapChars).ifEmpty { listOf(arrow.label) }.forEach {
                out += DrawnLabelLine(text = it, css = "kuml-body")
            }
        }
        return out
    }

    /** Draws [lines] centred in the rectangle of [placed]; a label that sits on its own line gets an opaque backdrop. */
    private fun emitPlannedLabel(
        placed: dev.kuml.layout.bridge.label.PlacedLabel,
        lines: List<DrawnLabelLine>,
        backdrop: String,
        builder: SvgBuilder,
    ) {
        val r = placed.rect
        // data-label-rect carries the planned rectangle, so tests can compare what is drawn with what was planned.
        builder.tag(
            name = "g",
            attrs =
                mapOf(
                    "class" to "kuml-edge-label",
                    "data-label-rect" to "${fmt(r.origin.x)} ${fmt(r.origin.y)} ${fmt(r.size.width)} ${fmt(r.size.height)}",
                ),
        ) {
            lines.forEachIndexed { i, line ->
                val textAttrs =
                    mapOf(
                        "x" to fmt(r.origin.x + r.size.width / 2f),
                        "y" to fmt(r.origin.y + 2f + 10f + i * dev.kuml.layout.bridge.label.Sysml2LabelSizing.LINE_PX),
                        "text-anchor" to "middle",
                    )
                // PAL 2C-b2: halo text first, fill text on top, same coordinates; the halo replaces the former backdrop rectangle.
                if (placed.onLine) {
                    tag(name = "text", attrs = mapOf("class" to line.css, "style" to haloStyle(backdrop)) + textAttrs) { text(line.text) }
                }
                tag(name = "text", attrs = mapOf("class" to line.css) + textAttrs) { text(line.text) }
            }
        }
    }

    /** An edge label waiting for [emitLabelsOnLine] (wave C2b): the texts of one edge, placed after all edges are known. */
    internal data class PendingEdgeLabel(
        val id: String,
        val stereotype: String?,
        val label: String?,
    )

    /** `null` when [metadata] carries no label text. */
    internal fun pendingLabelOf(
        id: String,
        metadata: Sysml2EdgeMetadata,
    ): PendingEdgeLabel? =
        if (metadata.stereotype == null &&
            metadata.label == null
        ) {
            null
        } else {
            PendingEdgeLabel(id = id, stereotype = metadata.stereotype, label = metadata.label)
        }

    /** The drawn lines of an activity edge label: stereotype lines first, then the label lines, wrapped like the other edge labels. */
    internal fun actLabelLines(p: PendingEdgeLabel): List<DrawnLabelLine> {
        val ls = ArrayList<DrawnLabelLine>()
        p.stereotype?.let { st ->
            wrapWords(text = st, maxChars = EDGE_LABEL_WRAP_CHARS).ifEmpty { listOf(st) }.forEach {
                ls +=
                    DrawnLabelLine(text = it, css = "kuml-stereotype")
            }
        }
        p.label?.let { l ->
            wrapWords(text = l, maxChars = EDGE_LABEL_WRAP_CHARS).ifEmpty { listOf(l) }.forEach {
                ls +=
                    DrawnLabelLine(text = it, css = "kuml-body")
            }
        }
        return ls
    }

    /**
     * Draws one planned label (PAL wave 2C-b2b): a group stamped with the planned rectangle, the [lines] centred in it, each
     * as a [HALO_PX] px halo text under the fill text. No backdrop rectangle. [groupClass] and [id] tell activity edge
     * labels and pin labels apart.
     */
    internal fun emitHaloLabelGroup(
        id: String,
        rect: dev.kuml.layout.Rect,
        lines: List<DrawnLabelLine>,
        backdrop: String,
        groupClass: String,
        builder: SvgBuilder,
    ) {
        builder.tag(
            name = "g",
            attrs =
                mapOf(
                    "class" to groupClass,
                    "data-label-id" to id,
                    "data-label-rect" to "${fmt(rect.origin.x)} ${fmt(rect.origin.y)} ${fmt(rect.size.width)} ${fmt(rect.size.height)}",
                ),
        ) {
            lines.forEachIndexed { i, line ->
                val textAttrs =
                    mapOf(
                        "x" to fmt(rect.origin.x + rect.size.width / 2f),
                        "y" to fmt(rect.origin.y + 2f + 10f + i * dev.kuml.layout.bridge.label.Sysml2LabelSizing.LINE_PX),
                        "text-anchor" to "middle",
                    )
                tag(name = "text", attrs = mapOf("class" to line.css, "style" to haloStyle(backdrop)) + textAttrs) { text(line.text) }
                tag(name = "text", attrs = mapOf("class" to line.css) + textAttrs) { text(line.text) }
            }
        }
    }

    /**
     * Places and draws the [pending] labels of REQ / UC / ACT edges (wave C2b): each label is centred on the
     * longest free segment of its own line, on an opaque backdrop chosen by [backdropFor] (box centre ->
     * colour). [allRoutes] are all drawn edges in canvas coordinates, [obstacles] node rectangles to avoid.
     * Painted as one group after every edge, so no later line runs over a label.
     */
    internal fun emitLabelsOnLine(
        pending: List<PendingEdgeLabel>,
        allRoutes: Map<String, EdgeRoute>,
        obstacles: List<dev.kuml.layout.Rect>,
        backdropFor: (dev.kuml.layout.Rect) -> String,
        builder: SvgBuilder,
        hardObstacles: List<dev.kuml.layout.Rect> = emptyList(),
    ) {
        if (pending.isEmpty()) return

        data class Line(
            val text: String,
            val css: String,
        )

        val lines =
            pending.associate { p ->
                val ls = ArrayList<Line>()
                p.stereotype?.let { st ->
                    wrapWords(text = st, maxChars = EDGE_LABEL_WRAP_CHARS).ifEmpty { listOf(st) }.forEach {
                        ls +=
                            Line(it, "kuml-stereotype")
                    }
                }
                p.label?.let { l ->
                    wrapWords(text = l, maxChars = EDGE_LABEL_WRAP_CHARS).ifEmpty { listOf(l) }.forEach { ls += Line(it, "kuml-body") }
                }
                p.id to ls
            }
        val requests =
            pending.mapNotNull { p ->
                val route = allRoutes[p.id] ?: return@mapNotNull null
                val ls = lines.getValue(p.id)
                val w = (ls.maxOfOrNull { it.text.length } ?: 1) * 6.2f + 8f
                val h = ls.size * LABEL_LINE_HEIGHT_PX + 4f
                dev.kuml.io.svg.sysml2
                    .LabelRequest(id = p.id, polyline = polylinePoints(route), w = w, h = h)
            }
        val placed =
            dev.kuml.io.svg.sysml2.Sysml2LabelPlacement.placeAll(
                requests = requests,
                allRoutes = allRoutes.mapValues { polylinePoints(it.value) },
                obstacles = obstacles,
                hardObstacles = hardObstacles,
            )
        builder.tag(name = "g", attrs = mapOf("class" to "kuml-edge-labels")) {
            for (id in placed.keys.sorted()) {
                val r = placed.getValue(id)
                tag(
                    name = "g",
                    attrs =
                        mapOf(
                            "class" to "kuml-edge-label",
                            "data-edge-id" to id,
                            "data-label-id" to id,
                            "data-label-rect" to "${fmt(r.origin.x)} ${fmt(r.origin.y)} ${fmt(r.size.width)} ${fmt(r.size.height)}",
                        ),
                ) {
                    tag(
                        name = "rect",
                        attrs =
                            mapOf(
                                "x" to fmt(r.origin.x),
                                "y" to fmt(r.origin.y),
                                "width" to fmt(r.size.width),
                                "height" to fmt(r.size.height),
                                "fill" to backdropFor(r),
                                "fill-opacity" to "1",
                                "stroke" to "none",
                            ),
                    )
                    lines.getValue(id).forEachIndexed { i, line ->
                        tag(
                            name = "text",
                            attrs =
                                mapOf(
                                    "class" to line.css,
                                    "x" to fmt(r.origin.x + r.size.width / 2f),
                                    "y" to fmt(r.origin.y + 2f + 9f + i * LABEL_LINE_HEIGHT_PX),
                                    "text-anchor" to "middle",
                                ),
                        ) { text(line.text) }
                    }
                }
            }
        }
    }

    /**
     * Emits all placed STM labels in one group, after every edge was drawn. [fills] maps an edge id
     * to the opaque backdrop colour of the container the label sits in; [defaultFill] is used
     * otherwise. Blocks are written in edge id order.
     */
    internal fun emitStmLabels(
        blocks: Map<String, StmLabelBlock>,
        fills: Map<String, String>,
        defaultFill: String,
        builder: SvgBuilder,
    ) {
        if (blocks.isEmpty()) return
        builder.tag(name = "g", attrs = mapOf("class" to "kuml-edge-labels")) {
            for (id in blocks.keys.sorted()) {
                emitStmBlock(edgeId = id, block = blocks.getValue(id), backdrop = fills[id] ?: defaultFill, builder = this)
            }
        }
    }

    /**
     * One STM label (PAL wave 2D-a): a group stamped with the planned rectangle (`data-label-rect`), the full text as
     * `<title>`, and every wrapped line twice, first as [HALO_PX] px halo in the [backdrop] colour, then as fill text.
     * No backdrop rectangle, so the label never covers a line or an arrow tip. Prose guards are italic.
     */
    private fun emitStmBlock(
        edgeId: String,
        block: StmLabelBlock,
        backdrop: String,
        builder: SvgBuilder,
    ) {
        val left = block.cx - block.w / 2f
        val topEdge = block.cy - block.h / 2f
        builder.tag(
            name = "g",
            attrs =
                mapOf(
                    "class" to "kuml-edge-label",
                    "data-edge-id" to edgeId,
                    "data-label-id" to edgeId,
                    "data-label-rect" to "${fmt(left)} ${fmt(topEdge)} ${fmt(block.w)} ${fmt(block.h)}",
                ),
        ) {
            tag(name = "title") { text(block.fullText) }
            val top = topEdge + STM_LABEL_PAD
            val (textX, anchorName) =
                when (block.anchor) {
                    StmLabelAnchor.Left -> (left + STM_LABEL_PAD) to "start"
                    StmLabelAnchor.Right -> (left + block.w - STM_LABEL_PAD) to "end"
                    StmLabelAnchor.Center -> block.cx to "middle"
                }
            for (halo in booleanArrayOf(true, false)) {
                block.lines.forEachIndexed { i, line ->
                    val base =
                        mapOf(
                            "class" to "kuml-body",
                            "x" to fmt(textX),
                            "y" to fmt(top + 9f + i * LABEL_LINE_HEIGHT_PX),
                            "text-anchor" to anchorName,
                        )
                    tag(name = "text", attrs = if (halo) base + ("style" to haloStyle(backdrop)) else base) {
                        val parts = line.parts
                        if (parts.none { it.kind == dev.kuml.sysml2.edge.EdgeLabelKind.GuardProse }) {
                            text(parts.joinToString(" ") { it.text })
                            return@tag
                        }
                        parts.forEachIndexed { k, part ->
                            val attrs =
                                if (part.kind == dev.kuml.sysml2.edge.EdgeLabelKind.GuardProse) {
                                    mapOf("font-style" to "italic")
                                } else {
                                    emptyMap()
                                }
                            tag(name = "tspan", attrs = attrs) { text(part.text + if (k < parts.size - 1) " " else "") }
                        }
                    }
                }
            }
        }
    }

    /** Halo stroke width of SysML 2 labels in px (PAL wave 2C-b2b: 2 px, drawn as a second text pass, never as a backdrop rectangle). */
    internal const val HALO_PX: Int = 2

    /** Inline style of a halo pass: background-coloured fill and a round [HALO_PX] px stroke (inline style beats the class rule). */
    internal fun haloStyle(backdrop: String): String = "fill:$backdrop;stroke:$backdrop;stroke-width:$HALO_PX;stroke-linejoin:round"

    private fun emitText(
        builder: SvgBuilder,
        content: String,
        x: Float,
        y: Float,
        cssClass: String,
        backdrop: String,
    ) {
        // Word-wrap long labels instead of drawing one unbounded-width line —
        // see EDGE_LABEL_WRAP_CHARS. `y` is the baseline of the FIRST line;
        // wrapped lines stack downward, away from the line/stereotype above.
        val lines = wrapWords(text = content, maxChars = EDGE_LABEL_WRAP_CHARS).ifEmpty { listOf(content) }
        val singleLineH = 12f
        val approxW = (lines.maxOf { it.length }) * 6.2f + 6f
        val approxH = singleLineH + (lines.size - 1) * LABEL_LINE_HEIGHT_PX
        // PAL 2C-b2: halo text under the fill text instead of a backdrop rectangle (readable where the label meets a line).
        val rectAttr = "${fmt(x - approxW / 2f)} ${fmt(y - singleLineH + 2f)} ${fmt(approxW)} ${fmt(approxH)}"
        builder.tag(name = "g", attrs = mapOf("class" to "kuml-label", "data-label-rect" to rectAttr)) {
            lines.forEachIndexed { index, line ->
                val attrs =
                    mapOf(
                        "x" to fmt(x),
                        "y" to fmt(y + index * LABEL_LINE_HEIGHT_PX),
                        "text-anchor" to "middle",
                    )
                tag(name = "text", attrs = mapOf("class" to cssClass, "style" to haloStyle(backdrop)) + attrs) { text(line) }
                tag(name = "text", attrs = mapOf("class" to cssClass) + attrs) { text(line) }
            }
        }
    }

    /**
     * Label anchor for an edge carrying mid-arrows: the segment midpoint, moved beside a vertical
     * line (right) so the label neither covers the line nor the arrow; item labels go to the left.
     */
    private fun besideAnchor(
        route: EdgeRoute,
        labelText: String?,
    ): Pair<Float, Float> {
        val (mx, my) = labelAnchor(route)
        val pts =
            when (route) {
                is EdgeRoute.Direct -> listOf(route.source, route.target)
                is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.Bezier -> listOf(route.source, route.target)
            }
        var best = 0
        var bestLen = -1f
        for (i in 0 until pts.size - 1) {
            val l = (pts[i + 1].x - pts[i].x).let { it * it } + (pts[i + 1].y - pts[i].y).let { it * it }
            if (l > bestLen) {
                bestLen = l
                best = i
            }
        }
        val vertical = kotlin.math.abs(pts[best + 1].y - pts[best].y) > kotlin.math.abs(pts[best + 1].x - pts[best].x)
        return if (vertical) (mx + estimateLabelHalfWidth(labelText) + 8f) to my else mx to my
    }

    /** Direction `(next point, source)` at the source end of the route. */
    private fun EdgeRoute.sourceArrowDirection(): Pair<Point, Point> =
        when (this) {
            is EdgeRoute.Direct -> target to source
            is EdgeRoute.OrthogonalRounded -> (waypoints.firstOrNull() ?: target) to source
            is EdgeRoute.TreeRounded -> (waypoints.firstOrNull() ?: target) to source
            is EdgeRoute.Bezier -> (controlPoints.firstOrNull() ?: target) to source
        }

    /** Role / multiplicity text beside the target end (drawn `Escape`-safe via `builder.text`). */
    private fun emitEndLabel(
        route: EdgeRoute,
        content: String,
        builder: SvgBuilder,
    ) {
        val (prev, tip) = route.arrowDirection()
        val dx = prev.x - tip.x
        val dy = prev.y - tip.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0.001f } ?: 1f
        val x = tip.x + dx / len * END_LABEL_OFFSET_PX + 6f
        val y = tip.y + dy / len * END_LABEL_OFFSET_PX + (if (dy > 0f) 10f else -2f)
        builder.tag(
            name = "text",
            attrs = mapOf("class" to "kuml-body", "x" to fmt(x), "y" to fmt(y), "text-anchor" to "start"),
        ) { text(content) }
    }

    /**
     * Item-flow arrows sitting on the longest segment, shifted [MID_ARROW_GAP_PX] apart; the
     * item type labels are stacked below the line.
     */
    private fun emitMidArrows(
        route: EdgeRoute,
        arrows: List<dev.kuml.sysml2.edge.Sysml2MidArrow>,
        builder: SvgBuilder,
        drawItemLabels: Boolean,
    ) {
        val pts =
            when (route) {
                is EdgeRoute.Direct -> listOf(route.source, route.target)
                is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.Bezier -> listOf(route.source, route.target)
            }
        var best = 0
        var bestLen = -1f
        for (i in 0 until pts.size - 1) {
            val l = (pts[i + 1].x - pts[i].x).let { it * it } + (pts[i + 1].y - pts[i].y).let { it * it }
            if (l > bestLen) {
                bestLen = l
                best = i
            }
        }
        val a = pts[best]
        val b = pts[best + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0.001f } ?: return
        val ux = dx / len
        val uy = dy / len
        val mx = a.x + dx * 0.62f
        val my = a.y + dy * 0.62f
        arrows.forEachIndexed { idx, arrow ->
            val shift = (idx - (arrows.size - 1) / 2f) * MID_ARROW_GAP_PX
            val cx = mx + ux * shift
            val cy = my + uy * shift
            val sgn = if (arrow.forward) 1f else -1f
            val tx = ux * sgn
            val ty = uy * sgn
            val nx = -ty
            val ny = tx
            val tipX = cx + tx * MID_ARROW_LEN_PX / 2f
            val tipY = cy + ty * MID_ARROW_LEN_PX / 2f
            val baseX = cx - tx * MID_ARROW_LEN_PX / 2f
            val baseY = cy - ty * MID_ARROW_LEN_PX / 2f
            val w = MID_ARROW_LEN_PX / 2f
            builder.tag(
                name = "polygon",
                attrs =
                    mapOf(
                        "points" to
                            "${fmt(tipX)},${fmt(tipY)} ${fmt(baseX + nx * w / 1.6f)},${fmt(baseY + ny * w / 1.6f)} " +
                            "${fmt(baseX - nx * w / 1.6f)},${fmt(baseY - ny * w / 1.6f)}",
                        "class" to "kuml-sysml2-port-dir",
                    ),
            )
            if (!drawItemLabels) return@forEachIndexed
            val vertical = kotlin.math.abs(uy) > kotlin.math.abs(ux)
            builder.tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(if (vertical) cx - 10f else cx),
                        "y" to fmt(if (vertical) cy + 4f else cy + 18f),
                        "text-anchor" to (if (vertical) "end" else "middle"),
                    ),
            ) { text(arrow.label) }
        }
    }

    private const val END_LABEL_OFFSET_PX: Float = 18f
    private const val MID_ARROW_LEN_PX: Float = 10f
    private const val MID_ARROW_GAP_PX: Float = 14f

    private fun toArrowStyle(head: Sysml2ArrowHead): ArrowStyle? =
        when (head) {
            Sysml2ArrowHead.FilledDiamond -> ArrowStyle.DIAMOND_FILLED
            Sysml2ArrowHead.OpenDiamond -> ArrowStyle.DIAMOND
            Sysml2ArrowHead.ContainmentCrosshair -> ArrowStyle.CROSSHAIR
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
