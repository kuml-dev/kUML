package dev.kuml.core.config

import dev.kuml.renderer.theme.core.StereotypeTheme

/**
 * Sparse override für [StereotypeTheme].
 *
 * Felder, die der Nutzer in `kuml.config.kts` nicht explizit gesetzt hat,
 * bleiben `null` — die Pipeline kopiert nur die nicht-null-Felder auf das
 * Basis-Theme. Damit verlieren Themes ihre eigenen Defaults nicht.
 */
public data class StereotypeOverridePatch(
    public val showTaggedValues: Boolean? = null,
    public val showConstraints: Boolean? = null,
    public val showIcons: Boolean? = null,
    public val joinSeparator: String? = null,
    public val headerFontSize: Float? = null,
    public val taggedValueFontSize: Float? = null,
    public val showFeatureStereotypes: Boolean? = null,
    public val featureStereotypeFontSize: Float? = null,
) {
    /** Wendet die nicht-null-Felder auf `base` an und gibt das gemergte Theme zurück. */
    public fun applyTo(base: StereotypeTheme): StereotypeTheme =
        base.copy(
            showTaggedValues = showTaggedValues ?: base.showTaggedValues,
            showConstraints = showConstraints ?: base.showConstraints,
            showIcons = showIcons ?: base.showIcons,
            joinSeparator = joinSeparator ?: base.joinSeparator,
            headerFontSize = headerFontSize ?: base.headerFontSize,
            taggedValueFontSize = taggedValueFontSize ?: base.taggedValueFontSize,
            showFeatureStereotypes = showFeatureStereotypes ?: base.showFeatureStereotypes,
            featureStereotypeFontSize = featureStereotypeFontSize ?: base.featureStereotypeFontSize,
        )
}
