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
 * @property highlightVertexIds Menge von Vertex-IDs, die mit einem Highlight-Ring markiert werden. Default: leer.
 * @property highlightStrokeColor Farbe des Highlight-Rings als CSS-Farbwert. Default: `"#FF6B35"`.
 * @property highlightStrokeWidthPx Stärke des Highlight-Rings in Pixeln. Default: `3`.
 * @property highlightRingOffsetPx Abstand des Highlight-Rings zum Knoten-Rand in Pixeln. Default: `4`.
 * @property paintCanvasBackground Wenn `true`, wird vor allen anderen Elementen
 *   ein `<rect>` über die gesamte ViewBox mit der Theme-Hintergrundfarbe
 *   gezeichnet — verhindert dass die Host-Fläche (z. B. Obsidian Dark) zwischen
 *   Knoten durchscheint. Default: `true` (V3.0.11).
 */
public data class SvgRenderOptions(
    public val prettyPrint: Boolean = true,
    public val includeXmlDeclaration: Boolean = true,
    public val paddingPx: Float = 16f,
    public val embedThemeAsComment: Boolean = true,
    // V2.0.43 — Behaviour Widget
    public val highlightVertexIds: Set<String> = emptySet(),
    public val highlightStrokeColor: String = "#FF6B35",
    public val highlightStrokeWidthPx: Float = 3f,
    public val highlightRingOffsetPx: Float = 4f,
    // V3.0.11 — Canvas-Background gegen transparenten SVG-Hintergrund auf
    // dunklen Host-Flächen (Obsidian Dark, Browser Dark Mode etc.).
    public val paintCanvasBackground: Boolean = true,
) {
    public companion object {
        /** Standard-Optionen: Pretty-Print an, XML-Deklaration an, 16 px Padding, Theme-Kommentar an. */
        public val DEFAULT: SvgRenderOptions = SvgRenderOptions()
    }
}
