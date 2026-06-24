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
}
