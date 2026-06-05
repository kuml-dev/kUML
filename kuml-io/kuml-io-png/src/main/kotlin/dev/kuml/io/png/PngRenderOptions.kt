package dev.kuml.io.png

import dev.kuml.renderer.theme.core.KumlColor

/**
 * Optionen für den PNG-Renderer.
 *
 * Beispiel:
 * ```kotlin
 * val options = PngRenderOptions(widthPx = 2048, transparent = true)
 * val png = KumlPngRenderer.toPng(svg, options)
 * ```
 *
 * @property widthPx Zielbreite in Pixel. Höhe wird proportional aus dem SVG viewBox abgeleitet.
 * @property backgroundColor Hintergrundfarbe. Default: [KumlColor.White]. Wird ignoriert wenn [transparent] = true.
 * @property transparent Wenn true, wird ein Alpha-Channel erzeugt und [backgroundColor] ignoriert.
 * @see KumlPngRenderer
 */
public data class PngRenderOptions(
    public val widthPx: Int = 1024,
    public val backgroundColor: KumlColor? = KumlColor.White,
    public val transparent: Boolean = false,
) {
    public companion object {
        /** Standard-Optionen: 1024 px breit, weißer Hintergrund. */
        public val DEFAULT: PngRenderOptions = PngRenderOptions()
    }
}
