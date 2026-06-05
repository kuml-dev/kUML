package dev.kuml.renderer.theme.core

import kotlinx.serialization.Serializable

/**
 * Framework-neutrales Theme. Wird vom SVG-Renderer direkt konsumiert
 * und von `kuml-renderer/kuml-themes` in Compose-Werte adaptiert.
 *
 * Beispiel:
 * ```kotlin
 * val theme: KumlTheme = PlainTheme()
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, theme)
 * ```
 *
 * @property name Menschenlesbarer Theme-Bezeichner, z.B. `"Plain"`.
 * @property colors Farbpalette.
 * @property typography Schriftart-Palette.
 * @property borders Rahmen- und Strich-Maße.
 *
 * @see KumlColors
 * @see KumlTypography
 * @see KumlBorders
 * @see PlainTheme
 */
@Serializable
public data class KumlTheme(
    public val name: String,
    public val colors: KumlColors,
    public val typography: KumlTypography,
    public val borders: KumlBorders,
)
