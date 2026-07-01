package dev.kuml.renderer.theme.core

internal class ElegantThemeProvider : KumlThemeProvider {
    override val name: String = "elegant"

    override fun theme(): KumlTheme = ElegantTheme()
}
