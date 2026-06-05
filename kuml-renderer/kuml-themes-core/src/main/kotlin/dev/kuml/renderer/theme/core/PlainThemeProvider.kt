package dev.kuml.renderer.theme.core

internal class PlainThemeProvider : KumlThemeProvider {
    override val name: String = "plain"

    override fun theme(): KumlTheme = PlainTheme()
}
