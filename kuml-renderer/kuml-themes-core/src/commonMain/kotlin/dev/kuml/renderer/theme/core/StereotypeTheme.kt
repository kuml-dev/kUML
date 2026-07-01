package dev.kuml.renderer.theme.core

import kotlinx.serialization.Serializable

/**
 * Theme-Konfiguration für Stereotyp-Rendering im SVG-Renderer.
 *
 * Steuert, wie `«stereotype»`-Header, Tagged-Value-Compartments und Constraint-Anzeige
 * gerendert werden. In `kuml.config.kts` aktivierbar via:
 * ```kotlin
 * render {
 *     stereotypes {
 *         showTaggedValues = true
 *         joinSeparator    = " | "
 *     }
 * }
 * ```
 *
 * @property showTaggedValues Tagged-Value-Compartment `{tag = value}` einblenden. Default: `false`.
 * @property showConstraints OCL-Constraint-Notes einblenden (V2). Default: `false`.
 * @property showIcons Icon-Rendering (V2). Default: `true` (reserviert, hat in V1.1 keine Wirkung).
 * @property joinSeparator Trennzeichen zwischen mehreren Stereotyp-Namen. Default: `", "`.
 * @property headerFontSize Schriftgröße der Stereotyp-Headerzeile in pt. Default: `10` (kleiner als Klassenname).
 * @property taggedValueFontSize Schriftgröße der Tagged-Value-Zeilen in pt. Default: `9`.
 * @property showFeatureStereotypes `«Stereotype»`-Präfix vor Operations-/Attribut-Zeilen einblenden. Default: `true`.
 *   Analoger Toggle zu [showTaggedValues]. Kann via `PlainTheme().copy(stereotypes = ...)` deaktiviert werden.
 * @property featureStereotypeFontSize Schriftgröße des kursiven Feature-Stereotyp-Präfixes in pt. Default: `9`.
 */
@Serializable
public data class StereotypeTheme(
    public val showTaggedValues: Boolean = false,
    public val showConstraints: Boolean = false,
    public val showIcons: Boolean = true,
    public val joinSeparator: String = ", ",
    public val headerFontSize: Float = 10f,
    public val taggedValueFontSize: Float = 9f,
    public val showFeatureStereotypes: Boolean = true,
    public val featureStereotypeFontSize: Float = 9f,
) {
    public companion object {
        /** Standard-Konfiguration: keine Tagged Values, Komma als Trenner. */
        public val Default: StereotypeTheme = StereotypeTheme()
    }
}
