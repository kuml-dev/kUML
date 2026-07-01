package dev.kuml.renderer.theme.core

/**
 * Verspieltes Theme — kräftige, fröhliche Farben mit rundem Geometrie-Stil.
 *
 * Ausgerichtet auf informelle Kontexte (Workshops, Brainstorming-Sessions,
 * Lehrunterlagen, Onboarding-Materialien): hoher Kontrast, sattes Akzent-Spiel
 * und abgerundete Ecken erzeugen eine zugewandte, einladende Anmutung.
 *
 * | Slot         | Hex      | Rolle                              |
 * |--------------|----------|------------------------------------|
 * | background   | #FFFDF7  | Pastell-Creme (Canvas)             |
 * | foreground   | #1A1240  | Tinten-Indigo (Texte)              |
 * | border       | #5E3FBE  | Helles Royal-Violett (Rahmen)      |
 * | muted        | #E89AB8  | Soft-Pink (Stereotypen)            |
 * | accent       | #FF7A45  | Lebendiges Korallenrot (Akzent)    |
 * | edge         | #2DB39A  | Frisches Teal (Kanten)             |
 * | edgeMuted    | #B9E0D6  | Mint (gestrichelte Kanten)         |
 *
 * Typografie nutzt einen rounded Sans-Serif (Nunito/Quicksand) für die
 * freundliche Optik, leicht größere Standardgrößen für bessere Lesbarkeit
 * an Whiteboards/Bildschirmen. Großzügiger Eckradius (12 px) und kräftige
 * Strichstärken verstärken die runde Geometrie.
 *
 * Beispiel:
 * ```kotlin
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlayfulTheme())
 * ```
 *
 * @see KumlTheme
 * @see PlainTheme
 * @see KumlBrandTheme
 * @see ElegantTheme
 */
@Suppress("FunctionName")
public fun PlayfulTheme(): KumlTheme {
    val colors =
        KumlColors(
            background = KumlColor(0xFFFDF7), // Pastell-Creme
            foreground = KumlColor(0x1A1240), // Tinten-Indigo
            border = KumlColor(0x5E3FBE), // Helles Royal-Violett
            muted = KumlColor(0xE89AB8), // Soft-Pink
            accent = KumlColor(0xFF7A45), // Lebendiges Korallenrot
            edge = KumlColor(0x2DB39A), // Frisches Teal
            edgeMuted = KumlColor(0xB9E0D6), // Mint
        )
    val typography =
        KumlTypography(
            title = KumlFont(PLAYFUL_FONT_STACK, sizePt = 15f, weight = 700),
            subtitle = KumlFont(PLAYFUL_FONT_STACK, sizePt = 13f, weight = 600),
            body = KumlFont(PLAYFUL_FONT_STACK, sizePt = 12f, weight = 500),
            small = KumlFont(PLAYFUL_FONT_STACK, sizePt = 10f, weight = 500),
            stereotype = KumlFont(PLAYFUL_FONT_STACK, sizePt = 11f, italic = true),
        )
    val borders =
        KumlBorders(
            thinPx = 1.5f,
            regularPx = 2f,
            thickPx = 3f,
            cornerRadiusPx = 12f,
        )
    return KumlTheme(
        name = "Playful",
        colors = colors,
        typography = typography,
        borders = borders,
    )
}

private const val PLAYFUL_FONT_STACK = "Nunito, Quicksand, Comic Neue, system-ui, sans-serif"
