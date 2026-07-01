package dev.kuml.renderer.theme.core

/**
 * Markentheme in den offiziellen kUML-Farben: Navy, Gold, Off-White.
 *
 * Die Palette ist direkt aus dem Design-Token-Set der Webseite kuml.dev
 * (`src/styles/tokens.css`) übernommen — damit rendern Diagramme im
 * gleichen Look-and-Feel wie die Marke selbst:
 *
 * | Slot         | Hex      | Rolle                                |
 * |--------------|----------|--------------------------------------|
 * | background   | #FFFFFF  | Reines Weiß (Canvas — neutral)       |
 * | nodeFill     | #F8F5F0  | Off-White (Knoten-Inneres, dezent)   |
 * | foreground   | #0D1525  | Ink (Texte, Icons)                   |
 * | border       | #1D2B4F  | Navy (Knoten-Rahmen)                 |
 * | muted        | #6B7A99  | Slate-blue (Stereotypen, Annotat.)   |
 * | accent       | #C49A2E  | Gold (Hervorhebungen)                |
 * | edge         | #1D2B4F  | Navy (Pfeile, Beziehungen)           |
 * | edgeMuted    | #6B7A99  | Slate-blue (gestrichelte Kanten)     |
 *
 * **Canvas vs. Knoten**: Der Diagramm-Hintergrund ist **reines Weiß** (#FFFFFF),
 * damit sich Diagramme nahtlos in jeden weißen Kontext einfügen (Webseite,
 * Slides, Print). Die Knoten selbst tragen eine dezente Off-White-Füllung
 * (#F8F5F0) — der subtile Warm-Ton hebt sie vom Canvas ab und passt zur
 * Marken-UI ohne den umgebenden Kontext zu stören.
 *
 * Typografie nutzt den Geist-/Inter-Stack der Webseite mit System-Fallback,
 * Standard-Diagrammgrößen. Etwas größerer Eckradius (8 px) und leicht
 * kräftigere `thickPx`-Stärke verleihen Knoten dieselbe weiche, moderne
 * Anmutung wie die Marken-UI.
 *
 * Beispiel:
 * ```kotlin
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, KumlBrandTheme())
 * ```
 *
 * @see KumlTheme
 * @see PlainTheme
 */
@Suppress("FunctionName")
public fun KumlBrandTheme(): KumlTheme {
    val colors =
        KumlColors(
            background = KumlColor(0xFFFFFF), // Reines Weiß (Canvas neutral)
            foreground = KumlColor(0x0D1525), // Ink
            border = KumlColor(0x1D2B4F), // Navy
            muted = KumlColor(0x6B7A99), // Slate-blue
            accent = KumlColor(0xC49A2E), // Gold
            edge = KumlColor(0x1D2B4F), // Navy
            edgeMuted = KumlColor(0x6B7A99), // Slate-blue
            nodeFill = KumlColor(0xF8F5F0), // Off-White (dezent getönte Knoten)
        )
    val typography =
        KumlTypography(
            title = KumlFont(BRAND_FONT_STACK, sizePt = 14f, weight = 700),
            subtitle = KumlFont(BRAND_FONT_STACK, sizePt = 12f, weight = 600),
            body = KumlFont(BRAND_FONT_STACK, sizePt = 11f),
            small = KumlFont(BRAND_FONT_STACK, sizePt = 9f),
            stereotype = KumlFont(BRAND_FONT_STACK, sizePt = 10f, italic = true),
        )
    val borders =
        KumlBorders(
            thinPx = 1f,
            regularPx = 1.5f,
            thickPx = 2.5f,
            cornerRadiusPx = 8f,
        )
    return KumlTheme(
        name = "kUML",
        colors = colors,
        typography = typography,
        borders = borders,
    )
}

private const val BRAND_FONT_STACK = "Geist, Inter, system-ui, sans-serif"
