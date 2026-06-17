package dev.kuml.plugin.examples.pdv

import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.api.theme.KumlThemePlugin
import dev.kuml.renderer.theme.core.KumlBorders
import dev.kuml.renderer.theme.core.KumlColor
import dev.kuml.renderer.theme.core.KumlColors
import dev.kuml.renderer.theme.core.KumlFont
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.KumlTypography

/**
 * PdV Branding Theme — reference implementation of [KumlThemePlugin].
 *
 * Palette: Aureolin #FFED00 · Black #000000 · Ucla Gold #FAB500 ·
 *          Biscay #1D2968 · Bay of Many #20358C · Green Blue #186CB4 · Violet Red #D00080
 * Typography: Inter (matching PdV Styleguide v2)
 *
 * Licensed under Apache-2.0. Usage of the PdV name/branding is permitted
 * exclusively in PdV-related political information materials.
 */
public class PdvThemePlugin : KumlThemePlugin {
    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = "dev.kuml.plugin.theme.pdv",
            name = "PdV Branding Theme",
            version = PluginVersion(1, 0, 0),
            kumlVersionRange = KumlVersionRange(">=0.12.0"),
            capabilities = setOf(PluginCapability.THEME),
            maintainer = "Partei der Vernunft",
            homepage = "https://parteidervernunft.de",
        )

    override fun themes(): List<KumlTheme> = listOf(pdvLight(), pdvDark())

    public companion object {
        // PdV palette
        public val AUREOLIN: KumlColor = KumlColor(0xFFED00)
        public val PDV_BLACK: KumlColor = KumlColor(0x000000)
        public val UCLA_GOLD: KumlColor = KumlColor(0xFAB500)
        public val BISCAY: KumlColor = KumlColor(0x1D2968)
        public val BAY_OF_MANY: KumlColor = KumlColor(0x20358C)
        public val GREEN_BLUE: KumlColor = KumlColor(0x186CB4)
        public val VIOLET_RED: KumlColor = KumlColor(0xD00080)
        public val WHITE: KumlColor = KumlColor(0xFFFFFF)
        public val LIGHT_GREY: KumlColor = KumlColor(0xF5F5F5)

        public fun pdvLight(): KumlTheme =
            KumlTheme(
                name = "pdv-light",
                colors =
                    KumlColors(
                        background = WHITE,
                        foreground = PDV_BLACK,
                        border = BISCAY,
                        muted = KumlColor(0xCCCCCC),
                        accent = AUREOLIN,
                        edge = BISCAY,
                        edgeMuted = GREEN_BLUE,
                    ),
                typography =
                    KumlTypography(
                        title = KumlFont("Inter", sizePt = 14f, weight = 700),
                        subtitle = KumlFont("Inter", sizePt = 12f, weight = 600),
                        body = KumlFont("Inter", sizePt = 11f, weight = 400),
                        small = KumlFont("Inter", sizePt = 9f, weight = 400),
                        stereotype = KumlFont("Inter", sizePt = 9f, weight = 400, italic = true),
                    ),
                borders = KumlBorders(thinPx = 1f, regularPx = 1.5f, thickPx = 2.5f, cornerRadiusPx = 3f),
            )

        public fun pdvDark(): KumlTheme =
            KumlTheme(
                name = "pdv-dark",
                colors =
                    KumlColors(
                        background = BISCAY,
                        foreground = WHITE,
                        border = AUREOLIN,
                        muted = KumlColor(0x4A5A8A),
                        accent = UCLA_GOLD,
                        edge = AUREOLIN,
                        edgeMuted = GREEN_BLUE,
                    ),
                typography =
                    KumlTypography(
                        title = KumlFont("Inter", sizePt = 14f, weight = 700),
                        subtitle = KumlFont("Inter", sizePt = 12f, weight = 600),
                        body = KumlFont("Inter", sizePt = 11f, weight = 400),
                        small = KumlFont("Inter", sizePt = 9f, weight = 400),
                        stereotype = KumlFont("Inter", sizePt = 9f, weight = 400, italic = true),
                    ),
                borders = KumlBorders(thinPx = 1f, regularPx = 1.5f, thickPx = 2.5f, cornerRadiusPx = 3f),
            )
    }
}
