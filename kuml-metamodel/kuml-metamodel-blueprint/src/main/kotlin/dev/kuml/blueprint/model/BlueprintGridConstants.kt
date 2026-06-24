package dev.kuml.blueprint.model

/**
 * Shared grid-geometry constants for the Journey-Map / Service-Blueprint view.
 *
 * Single source of truth consumed by both the SVG renderer ([BlueprintGeometry]
 * in `kuml-io-svg`) and the layout bridge ([BlueprintLayoutBridge] in
 * `kuml-layout-bridge`). Before V3.1.24 both classes carried identical
 * hardcoded values independently — a silent divergence risk.
 *
 * V3.1.24
 */
public object BlueprintGridConstants {
    public const val LABEL_COLUMN_WIDTH: Double = 132.0
    public const val COLUMN_WIDTH: Double = 200.0
    public const val ROW_HEIGHT: Double = 96.0
    public const val EMOTION_BAND_HEIGHT: Double = 110.0
    public const val HEADER_HEIGHT: Double = 36.0
    public const val PADDING: Double = 16.0
}
