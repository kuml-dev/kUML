package dev.kuml.blueprint.model

/**
 * Shared grid-geometry constants for the Journey-Map / Service-Blueprint view.
 *
 * Single source of truth consumed by both the SVG renderer ([BlueprintGeometry]
 * in `kuml-io-svg`) and the layout bridge ([BlueprintLayoutBridge] in
 * `kuml-layout-bridge`). Before V3.1.24 both classes carried identical
 * hardcoded values independently — a silent divergence risk.
 *
 * V3.1.28: [ROW_HEIGHT] increased from 96 → 112 to create a 24 px clearance
 * zone beneath each step card.  The card inner height stays at 80 px
 * ([CARD_MARGIN_TOP] 8 + inner 80 + [CARD_MARGIN_BOTTOM] 24 = 112).
 * [SEPARATOR_LABEL_OFFSET] controls how far the Shostack-line captions sit
 * above the separator line — 12 px places the text comfortably inside the
 * 24 px clearance zone, clear of both the card border and the line itself.
 *
 * V3.1.24
 */
public object BlueprintGridConstants {
    public const val LABEL_COLUMN_WIDTH: Double = 132.0
    public const val COLUMN_WIDTH: Double = 200.0

    /** Total height of one layer-band row (card + margins). */
    public const val ROW_HEIGHT: Double = 112.0

    /** Top margin between band edge and step-card rect. */
    public const val CARD_MARGIN_TOP: Double = 8.0

    /**
     * Bottom margin between step-card rect and the band's lower edge.
     *
     * Larger than [CARD_MARGIN_TOP] so Shostack separator-line captions have
     * 24 px of clear space between the card border and the separator line.
     */
    public const val CARD_MARGIN_BOTTOM: Double = 24.0

    /**
     * Baseline distance (px) of a Shostack separator-line caption above the
     * separator line.  12 px places the text at roughly mid-height of the
     * [CARD_MARGIN_BOTTOM] clearance zone.
     */
    public const val SEPARATOR_LABEL_OFFSET: Double = 12.0

    public const val EMOTION_BAND_HEIGHT: Double = 110.0
    public const val HEADER_HEIGHT: Double = 36.0
    public const val PADDING: Double = 16.0

    // ── Content-aware row height (fix) ─────────────────────────────────────
    //
    // The constants below mirror per-character/per-line metrics in
    // `dev.kuml.io.svg.blueprint.BlueprintStepSvg` (kuml-io-svg) — duplicated
    // here (rather than imported) because BOTH `BlueprintGeometry` (SVG
    // renderer, kuml-io-svg) and `BlueprintLayoutBridge` (kuml-layout-bridge)
    // need [contentAwareRowHeight], and kuml-io-svg deliberately does not
    // depend on kuml-layout-bridge (established house convention, see e.g.
    // the NESTED_* constants in `UmlContentSizeProvider`). This metamodel
    // module is the one dependency both already share. MUST stay in sync
    // with `AVG_CHAR_WIDTH_PX` / `TITLE_LINE_HEIGHT` in `BlueprintStepSvg.kt`.

    private const val AVG_CHAR_WIDTH_PX: Double = 6.5
    private const val TITLE_LINE_HEIGHT: Double = 14.0

    /** Horizontal card margin (left + right each), mirrors `mSide` in `renderStepCard`. */
    private const val CARD_MARGIN_SIDE: Double = 8.0

    /** iconSize(16) + 8px margin/gap reserve when a step shows an actor-role icon. */
    private const val ACTOR_ICON_RESERVE_PX: Double = 24.0

    /** First-line baseline offset from the card top, mirrors `y + 20.0` in `renderStepCard`. */
    private const val FIRST_LINE_TOP_OFFSET: Double = 20.0

    /** Extra clearance below the last line's baseline (descenders + breathing room). */
    private const val BOTTOM_TEXT_CLEARANCE: Double = 10.0

    /**
     * Extra bottom clearance reserved when any step in the diagram has a
     * pain point — makes room for the pain-dot/caption row
     * `renderStepCard` draws above the touchpoint-icon row (see design
     * decision: pain dot + caption share their own row rather than the
     * touchpoint-icon row, to avoid colliding with the icons). 22px (not
     * just one 14px text line) leaves a clear ~6px gap above the touchpoint
     * icons' top edge — MUST stay in sync with the `y + h - 32` offset in
     * `renderStepCard` (`BlueprintStepSvg.kt`, kuml-io-svg). Applied
     * diagram-wide (not per-cell) like the rest of [contentAwareRowHeight],
     * matching the uniform-grid design.
     */
    private const val PAIN_TEXT_RESERVE: Double = 22.0

    /** Card interior height when nothing needs more room (matches the pre-fix fixed 80px). */
    private const val CARD_INNER_HEIGHT_DEFAULT: Double = ROW_HEIGHT - CARD_MARGIN_TOP - CARD_MARGIN_BOTTOM

    // ── Touchpoint legend (fix: touchpoint names were never rendered) ──────
    //
    // Touchpoint icons only ever drew the channel glyph, never the
    // touchpoint's own name — readers had no way to tell two same-channel
    // touchpoints apart (e.g. two different push notifications) without
    // reading the source DSL. Each touchpoint actually referenced by a step
    // gets a small numbered badge on its icon; the legend below the grid
    // maps badge number -> touchpoint name, keyed by individual touchpoint
    // (not just channel/symbol), so distinct touchpoints stay distinguishable.

    /** Diameter of a legend badge circle (mirrors the badge drawn on touchpoint icons). */
    public const val LEGEND_BADGE_DIAMETER: Double = 16.0

    /** Gap between a legend badge and its label text. */
    public const val LEGEND_BADGE_TEXT_GAP: Double = 4.0

    /** Gap between one legend chip and the next. */
    public const val LEGEND_CHIP_GAP: Double = 20.0

    /** Vertical height per wrapped legend row. */
    public const val LEGEND_ROW_HEIGHT: Double = 22.0

    /** Gap between the last diagram row and the legend's first row. */
    public const val LEGEND_TOP_PADDING: Double = 12.0

    /** Avg width of a 9pt legend-label character (mirrors kuml-small typography). */
    private const val LEGEND_CHAR_WIDTH_PX: Double = 5.4

    /**
     * Counts how many lines [text] wraps to at [maxWidthPx] — mirrors the
     * word-boundary algorithm in `wrapText()` (`BlueprintStepSvg.kt`,
     * kuml-io-svg) exactly, just returning a count instead of the line list.
     */
    private fun wrappedLineCount(
        text: String,
        maxWidthPx: Double,
    ): Int {
        val maxChars = (maxWidthPx / AVG_CHAR_WIDTH_PX).toInt().coerceAtLeast(1)
        val words = text.split(" ")
        var lines = 0
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length <= maxChars) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines++
                current = word
            }
        }
        if (current.isNotEmpty()) lines++
        return lines.coerceAtLeast(1)
    }

    /**
     * Content-aware row height (px) for the Journey-Map / Service-Blueprint
     * grid — grows [ROW_HEIGHT] so the tallest wrapped step title in [model]
     * fits inside its card without crossing the top or bottom border.
     *
     * Bug this fixes: [ROW_HEIGHT] used to be a hardcoded constant regardless
     * of content. `renderStepCard` (kuml-io-svg) wraps long titles onto
     * multiple lines and vertically centered the block by shifting the first
     * line up by half the extra height — for titles wrapping to 3+ lines that
     * pushed the first baseline above the card's top border (e.g. "Discovers
     * Pepela Portal via search or word of mouth in Tbilisi; visits
     * pepela.ge" in a Pepela Portal user-journey diagram), and titles
     * wrapping to 6+ lines ran past the bottom border regardless of
     * centering, because the fixed 80px card interior only fits ~5 lines at
     * [TITLE_LINE_HEIGHT].
     *
     * A single diagram-wide row height (rather than a per-row or per-cell
     * height) keeps the grid uniform — matching the existing "every row
     * shares [ROW_HEIGHT]" design — only the value becomes content-aware
     * instead of hardcoded. Cells with short titles simply get extra
     * whitespace below the text, which is preferable to overflow.
     */
    public fun contentAwareRowHeight(model: BlueprintModel): Double {
        val maxLines =
            model.steps.maxOfOrNull { step ->
                val textWidth =
                    COLUMN_WIDTH - 2 * CARD_MARGIN_SIDE -
                        (if (step.actorRef != null) ACTOR_ICON_RESERVE_PX else 0.0)
                wrappedLineCount(step.name ?: step.id, textWidth)
            } ?: 1
        val hasPain = model.steps.any { it.painPoint != null }
        val bottomClearance = BOTTOM_TEXT_CLEARANCE + (if (hasPain) PAIN_TEXT_RESERVE else 0.0)
        val neededCardInnerHeight = FIRST_LINE_TOP_OFFSET + (maxLines - 1) * TITLE_LINE_HEIGHT + bottomClearance
        val cardInnerHeight = maxOf(CARD_INNER_HEIGHT_DEFAULT, neededCardInnerHeight)
        return CARD_MARGIN_TOP + cardInnerHeight + CARD_MARGIN_BOTTOM
    }

    /**
     * Touchpoints actually referenced by at least one step, in declaration
     * order, each paired with a stable 1-based badge number. Touchpoints
     * declared but never referenced by a visible step are silently omitted
     * (no orphaned legend entries).
     */
    public fun legendEntries(model: BlueprintModel): List<Pair<Int, Touchpoint>> {
        val usedIds = model.steps.flatMap { it.touchpointRefs }.toSet()
        return model.touchpoints
            .filter { it.id in usedIds }
            .mapIndexed { index, tp -> (index + 1) to tp }
    }

    /** Estimated rendered width of one legend chip (badge + gap + label + trailing gap). */
    private fun legendChipWidth(touchpoint: Touchpoint): Double {
        val label = touchpoint.name ?: touchpoint.id
        val textWidth = label.length * LEGEND_CHAR_WIDTH_PX
        return LEGEND_BADGE_DIAMETER + LEGEND_BADGE_TEXT_GAP + textWidth + LEGEND_CHIP_GAP
    }

    /**
     * Greedily wraps [entries] into rows that fit within [contentWidth] —
     * same left-to-right fill strategy as [wrappedLineCount]'s word-wrap,
     * except the unit is a whole chip (badge + label) rather than a word,
     * since a chip can't be split across rows.
     */
    public fun wrapLegendEntries(
        entries: List<Pair<Int, Touchpoint>>,
        contentWidth: Double,
    ): List<List<Pair<Int, Touchpoint>>> {
        if (entries.isEmpty()) return emptyList()
        val rows = mutableListOf<List<Pair<Int, Touchpoint>>>()
        var currentRow = mutableListOf<Pair<Int, Touchpoint>>()
        var currentWidth = 0.0
        for (entry in entries) {
            val w = legendChipWidth(entry.second)
            if (currentRow.isNotEmpty() && currentWidth + w > contentWidth) {
                rows += currentRow
                currentRow = mutableListOf()
                currentWidth = 0.0
            }
            currentRow += entry
            currentWidth += w
        }
        if (currentRow.isNotEmpty()) rows += currentRow
        return rows
    }

    /**
     * Height (px) of the touchpoint legend band for [model] at [contentWidth]
     * — `0.0` when no step references any touchpoint, so diagrams without
     * touchpoints render exactly as before (no empty legend band).
     */
    public fun legendHeight(
        model: BlueprintModel,
        contentWidth: Double,
    ): Double {
        val entries = legendEntries(model)
        if (entries.isEmpty()) return 0.0
        val rows = wrapLegendEntries(entries, contentWidth)
        return LEGEND_TOP_PADDING + rows.size * LEGEND_ROW_HEIGHT
    }
}
