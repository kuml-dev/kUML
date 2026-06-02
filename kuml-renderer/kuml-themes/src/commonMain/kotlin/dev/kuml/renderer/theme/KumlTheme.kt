package dev.kuml.renderer.theme

/**
 * Contract for all kUML visual themes.
 *
 * Composables in `kuml-renderer/kuml-kuiver` are theme-agnostic — every colour,
 * font and stroke measurement is pulled exclusively from the active [KumlTheme].
 *
 * Ship a custom theme by implementing this interface and passing it to
 * `KumlKuiverRenderer.Render(…, theme = myTheme)`.
 *
 * Example:
 * ```kotlin
 * object DarkTheme : KumlTheme {
 *     override val name = "Dark"
 *     override val colors = KumlColors(background = Color.Black, …)
 *     override val typography = PlainTheme.typography  // reuse
 *     override val borders = PlainTheme.borders
 * }
 * ```
 *
 * @see PlainTheme  the V1 default (black & white)
 * @see KumlColors
 * @see KumlTypography
 * @see KumlBorders
 */
public interface KumlTheme {
    /** Human-readable identifier for this theme (e.g. `"Plain"`, `"Dark"`). */
    public val name: String

    /** Colour palette. */
    public val colors: KumlColors

    /** Text styles. */
    public val typography: KumlTypography

    /** Border / stroke measurements. */
    public val borders: KumlBorders
}
