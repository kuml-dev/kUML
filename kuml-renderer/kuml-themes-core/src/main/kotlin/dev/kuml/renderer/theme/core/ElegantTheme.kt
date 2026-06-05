package dev.kuml.renderer.theme.core

/**
 * Elegantes Theme — gedeckte Warm-Grau-Palette mit dezentem Akzent.
 *
 * Ausgerichtet auf formelle Kontexte (Architektur-Dokumentation, Strategy
 * Decks, Whitepapers): wenig visueller Lärm, ein einziger warmer Akzent,
 * Serif-Schrift für eine klassische Anmutung.
 *
 * | Slot         | Hex      | Rolle                              |
 * |--------------|----------|------------------------------------|
 * | background   | #FAFAF7  | Warm-Off-White (Canvas)            |
 * | foreground   | #2A2520  | Dunkles Warm-Grau (Texte)          |
 * | border       | #4A4038  | Edles Braun-Grau (Knoten-Rahmen)   |
 * | muted        | #8A7F73  | Helles Warm-Grau (Stereotypen)     |
 * | accent       | #A0524D  | Dusty Rose (Hervorhebungen)        |
 * | edge         | #4A4038  | Edles Braun-Grau (Kanten)          |
 * | edgeMuted    | #B0A599  | Sand-Grau (gestrichelt)            |
 *
 * Typografie nutzt einen Serif-Stack (Garamond/Cambria), Standard-
 * Diagrammgrößen, etwas dünnere Strichstärken für die zurückhaltende Wirkung.
 *
 * Beispiel:
 * ```kotlin
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, ElegantTheme())
 * ```
 *
 * @see KumlTheme
 * @see PlainTheme
 * @see KumlBrandTheme
 * @see PlayfulTheme
 */
@Suppress("FunctionName")
public fun ElegantTheme(): KumlTheme {
    val colors =
        KumlColors(
            background = KumlColor(0xFAFAF7), // Warm-Off-White
            foreground = KumlColor(0x2A2520), // Dunkles Warm-Grau
            border = KumlColor(0x4A4038), // Edles Braun-Grau
            muted = KumlColor(0x8A7F73), // Helles Warm-Grau
            accent = KumlColor(0xA0524D), // Dusty Rose
            edge = KumlColor(0x4A4038), // Edles Braun-Grau
            edgeMuted = KumlColor(0xB0A599), // Sand-Grau
        )
    val typography =
        KumlTypography(
            title = KumlFont(ELEGANT_FONT_STACK, sizePt = 14f, weight = 600),
            subtitle = KumlFont(ELEGANT_FONT_STACK, sizePt = 12f, weight = 500),
            body = KumlFont(ELEGANT_FONT_STACK, sizePt = 11f),
            small = KumlFont(ELEGANT_FONT_STACK, sizePt = 9f),
            stereotype = KumlFont(ELEGANT_FONT_STACK, sizePt = 10f, italic = true),
        )
    val borders =
        KumlBorders(
            thinPx = 0.75f,
            regularPx = 1f,
            thickPx = 1.5f,
            cornerRadiusPx = 2f,
        )
    return KumlTheme(
        name = "Elegant",
        colors = colors,
        typography = typography,
        borders = borders,
    )
}

private const val ELEGANT_FONT_STACK = "EB Garamond, Garamond, Cambria, Georgia, serif"
