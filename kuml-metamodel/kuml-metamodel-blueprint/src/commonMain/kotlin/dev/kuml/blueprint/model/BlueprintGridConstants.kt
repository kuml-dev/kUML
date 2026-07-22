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

    /** Card interior height when nothing needs more room (matches the pre-fix fixed 80px). */
    private const val CARD_INNER_HEIGHT_DEFAULT: Double = ROW_HEIGHT - CARD_MARGIN_TOP - CARD_MARGIN_BOTTOM

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
        val neededCardInnerHeight = FIRST_LINE_TOP_OFFSET + (maxLines - 1) * TITLE_LINE_HEIGHT + BOTTOM_TEXT_CLEARANCE
        val cardInnerHeight = maxOf(CARD_INNER_HEIGHT_DEFAULT, neededCardInnerHeight)
        return CARD_MARGIN_TOP + cardInnerHeight + CARD_MARGIN_BOTTOM
    }
}
