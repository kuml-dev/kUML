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
 * @property highlightVertexIds Menge von Vertex-IDs, die mit einem SICHTBAREN Highlight-Ring markiert werden. Default: leer.
 * @property preparedHighlightVertexIds Menge von Vertex-IDs, für die ein Highlight-Ring emittiert
 *   wird, aber mit `visibility="hidden"` — bestimmt für Live-DOM-Patching (kUML Desktop-Simulation,
 *   siehe `dev.kuml.desktop.preview.SimulationHighlightPatcher`). Für statische Ausgaben
 *   (`kuml render`, Export, Website, Handbuch) **nicht** setzen. Default: leer.
 * @property highlightStrokeColor Farbe des Highlight-Rings als CSS-Farbwert. Default: `"#FF6B35"`
 *   (siehe [DEFAULT_HIGHLIGHT_STROKE_COLOR]).
 * @property highlightStrokeWidthPx Stärke des Highlight-Rings in Pixeln. Default: `3`.
 * @property highlightRingOffsetPx Abstand des Highlight-Rings zum Knoten-Rand in Pixeln. Default: `4`.
 * @property paintCanvasBackground Wenn `true`, wird vor allen anderen Elementen
 *   ein `<rect>` über die gesamte ViewBox mit der Theme-Hintergrundfarbe
 *   gezeichnet — verhindert dass die Host-Fläche (z. B. Obsidian Dark) zwischen
 *   Knoten durchscheint. Default: `true` (V3.0.11).
 * @property watermark Wenn `true`, wird unten rechts ein kleines,
 *   theme-konformes „Powered by kUML"-Label eingeblendet und die Canvas
 *   entsprechend vergrößert. Default: `false` — opt-in, unabhängig von der
 *   immer aktiven Attributions-XML-Kommentarzeile (siehe [SvgDocument.render]).
 */
public data class SvgRenderOptions(
    public val prettyPrint: Boolean = true,
    public val includeXmlDeclaration: Boolean = true,
    public val paddingPx: Float = 16f,
    public val embedThemeAsComment: Boolean = true,
    // V2.0.43 — Behaviour Widget
    public val highlightVertexIds: Set<String> = emptySet(),
    public val highlightStrokeColor: String = DEFAULT_HIGHLIGHT_STROKE_COLOR,
    public val highlightStrokeWidthPx: Float = 3f,
    public val highlightRingOffsetPx: Float = 4f,
    // V3.x — Live-Simulation von Zustandsautomaten im Editor (kUML Desktop). Rings for these
    // IDs are emitted but hidden (visibility="hidden") so the desktop can flip them
    // visible/invisible per simulation step via a DOM patch, without a second render pass.
    public val preparedHighlightVertexIds: Set<String> = emptySet(),
    // V3.0.11 — Canvas-Background gegen transparenten SVG-Hintergrund auf
    // dunklen Host-Flächen (Obsidian Dark, Browser Dark Mode etc.).
    public val paintCanvasBackground: Boolean = true,
    // "Powered by kUML" branding — opt-in visible watermark. Off by default on
    // every surface (CLI, Gradle plugin, MCP) because `DEFAULT` is the no-arg
    // constructor. See SvgDocument.render for the always-on attribution comment,
    // which is independent of this flag.
    public val watermark: Boolean = false,
) {
    public companion object {
        /** Standard-Optionen: Pretty-Print an, XML-Deklaration an, 16 px Padding, Theme-Kommentar an. */
        public val DEFAULT: SvgRenderOptions = SvgRenderOptions()

        /**
         * Default-Strichfarbe des Highlight-Rings — als benannte Konstante, damit
         * [dev.kuml.io.svg.KumlSvgRenderer]'s Farbauflösung erkennen kann, ob [highlightStrokeColor]
         * explizit vom Aufrufer gesetzt wurde oder noch den Default trägt (V3.x — Live-Simulation,
         * `KumlColors.activeStateStroke` füllt in letzterem Fall den Default).
         */
        public const val DEFAULT_HIGHLIGHT_STROKE_COLOR: String = "#FF6B35"
    }
}
