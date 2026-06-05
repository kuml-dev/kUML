package dev.kuml.renderer.theme.core

internal class PlayfulThemeProvider : KumlThemeProvider {
    override val name: String = "playful"

    override fun theme(): KumlTheme = PlayfulTheme()
}
