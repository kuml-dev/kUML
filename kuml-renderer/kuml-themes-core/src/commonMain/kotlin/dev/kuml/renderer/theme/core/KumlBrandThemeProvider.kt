package dev.kuml.renderer.theme.core

internal class KumlBrandThemeProvider : KumlThemeProvider {
    override val name: String = "kuml"

    override fun theme(): KumlTheme = KumlBrandTheme()
}
