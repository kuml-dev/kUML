package dev.kuml.renderer.theme.core

/**
 * Editorial-elegantes Theme — Pergament-Cream, Mahagoni-Tinte, Bordeaux-Akzent.
 *
 * Inspiriert vom klassischen Buchsatz und editorialem Design: ein einziger
 * warmer Cream-Untergrund, sehr feine Tintenlinien, scharfe Ecken (keine
 * Abrundung), Serif-Typografie mit zurückhaltender Strichstärke. Hervorgehoben
 * wird nur über eine einzige tiefe Bordeaux-Note — kein zweiter Akzent, keine
 * weichen Pastelltöne.
 *
 * Klar abgegrenzt vom `kuml`-Markentheme (Navy/Gold, abgerundete Ecken,
 * sans-serif): Elegant ist kühl-formell, klassisch, fast antiquarisch.
 *
 * | Slot         | Hex      | Rolle                                |
 * |--------------|----------|--------------------------------------|
 * | background   | #F4EDDD  | Pergament-Cream (Canvas)             |
 * | foreground   | #1C1814  | Espresso-Schwarz (Texte)             |
 * | border       | #4A2F22  | Mahagoni-Tinte (Knoten-Rahmen)       |
 * | muted        | #8B7A66  | Taupe (Stereotypen, Annotat.)        |
 * | accent       | #7A1F2D  | Tiefer Bordeaux (Hervorhebungen)     |
 * | edge         | #3D2418  | Sepia-Tinte (Kanten)                 |
 * | edgeMuted    | #B5A38B  | Champagner (gestrichelte Kanten)     |
 *
 * Typografie nutzt einen klassischen Serif-Stack (EB Garamond/Cormorant)
 * mit etwas größerem Titel-Grad und schmalerer Schrift — leicht-durchscheinende,
 * editoriale Anmutung. Borders sind **scharfkantig** (`cornerRadiusPx = 0`)
 * und sehr fein — wie gesetzter Druck.
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
            background = KumlColor(0xF4EDDD), // Pergament-Cream
            foreground = KumlColor(0x1C1814), // Espresso-Schwarz
            border = KumlColor(0x4A2F22), // Mahagoni-Tinte
            muted = KumlColor(0x8B7A66), // Taupe
            accent = KumlColor(0x7A1F2D), // Tiefer Bordeaux
            edge = KumlColor(0x3D2418), // Sepia-Tinte
            edgeMuted = KumlColor(0xB5A38B), // Champagner
        )
    val typography =
        KumlTypography(
            title = KumlFont(ELEGANT_FONT_STACK, sizePt = 15f, weight = 500),
            subtitle = KumlFont(ELEGANT_FONT_STACK, sizePt = 12f, weight = 400),
            body = KumlFont(ELEGANT_FONT_STACK, sizePt = 11f, weight = 400),
            small = KumlFont(ELEGANT_FONT_STACK, sizePt = 9f, weight = 400),
            stereotype = KumlFont(ELEGANT_FONT_STACK, sizePt = 10f, weight = 400, italic = true),
        )
    val borders =
        KumlBorders(
            thinPx = 0.5f, // sehr fein — wie gesetzter Druck
            regularPx = 0.75f, // klassische Buchsatz-Linie
            thickPx = 1.25f, // dezente Hervorhebung
            cornerRadiusPx = 0f, // scharfe Ecken: klassisch-editorial, kein Rundungs-Pop
        )
    return KumlTheme(
        name = "Elegant",
        colors = colors,
        typography = typography,
        borders = borders,
    )
}

private const val ELEGANT_FONT_STACK =
    "EB Garamond, Cormorant Garamond, Garamond, Cambria, Georgia, serif"
