package dev.kuml.plugin.api.theme

import dev.kuml.plugin.api.core.KumlPlugin
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Plugin SPI for kUML themes.
 *
 * A theme plugin contributes one or more [KumlTheme] instances to the
 * [dev.kuml.renderer.theme.core.ThemeRegistry]. Built-in themes
 * (Plain, kUML, Elegant, Playful) implement this interface directly.
 *
 * V3.0.27: SPI definition. Discovery + loading via [kuml-plugin-loader] (V3.0.28).
 */
public interface KumlThemePlugin : KumlPlugin {
    /** All themes contributed by this plugin. Must not be empty. */
    public fun themes(): List<KumlTheme>
}
