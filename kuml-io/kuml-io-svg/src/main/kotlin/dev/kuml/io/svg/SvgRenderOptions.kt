package dev.kuml.io.svg

/**
 * Optionen für den SVG-Renderer.
 *
 * Beispiel:
 * ```kotlin
 * val options = SvgRenderOptions(prettyPrint = false, includeXmlDeclaration = false)
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, options = options)
 * ```
 *
 * @property prettyPrint Wenn `true`, wird der SVG-Output eingerückt und zeilengetrennt. Default: `true`.
 * @property includeXmlDeclaration Wenn `true`, wird `<?xml version="1.0" encoding="UTF-8"?>` vorangestellt.
 * @property paddingPx Innenabstand um das gesamte Diagramm in Pixeln. Default: `16`.
 * @property embedThemeAsComment Wenn `true`, wird `<!-- theme: … · engine: … -->` eingebettet.
 */
public data class SvgRenderOptions(
    public val prettyPrint: Boolean = true,
    public val includeXmlDeclaration: Boolean = true,
    public val paddingPx: Float = 16f,
    public val embedThemeAsComment: Boolean = true,
) {
    public companion object {
        /** Standard-Optionen: Pretty-Print an, XML-Deklaration an, 16 px Padding, Theme-Kommentar an. */
        public val DEFAULT: SvgRenderOptions = SvgRenderOptions()
    }
}
