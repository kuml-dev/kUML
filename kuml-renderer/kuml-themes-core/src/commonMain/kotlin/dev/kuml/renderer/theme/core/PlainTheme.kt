package dev.kuml.renderer.theme.core

/**
 * Eingebautes Schwarz-Weiß-Theme — der V1-Standard für alle kUML-Renderer.
 *
 * Verwendet den System-Standardfont in klassischen UML-Diagrammgrößen.
 * Keine zusätzlichen Font-Ressourcen erforderlich.
 *
 * Beispiel:
 * ```kotlin
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())
 * ```
 *
 * @see KumlTheme
 */
@Suppress("FunctionName")
public fun PlainTheme(): KumlTheme {
    val colors =
        KumlColors(
            background = KumlColor.White,
            foreground = KumlColor.Black,
            border = KumlColor.Black,
            muted = KumlColor(0x666666),
            accent = KumlColor.Black,
            edge = KumlColor.Black,
            edgeMuted = KumlColor(0x999999),
        )
    val typography =
        KumlTypography(
            title = KumlFont("system-ui, sans-serif", sizePt = 14f, weight = 700),
            subtitle = KumlFont("system-ui, sans-serif", sizePt = 12f, weight = 600),
            body = KumlFont("system-ui, sans-serif", sizePt = 11f),
            small = KumlFont("system-ui, sans-serif", sizePt = 9f),
            stereotype = KumlFont("system-ui, sans-serif", sizePt = 10f, italic = true),
        )
    val borders = KumlBorders(thinPx = 1f, regularPx = 1.5f, thickPx = 2f, cornerRadiusPx = 4f)
    return KumlTheme(name = "Plain", colors = colors, typography = typography, borders = borders)
}
