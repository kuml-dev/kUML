package dev.kuml.plugin.api.core

import kotlinx.serialization.Serializable

/** Declares which extension category a plugin contributes to. */
@Serializable
public enum class PluginCapability {
    /** Contributes one or more [dev.kuml.plugin.api.theme.KumlThemePlugin] themes. */
    THEME,

    /** Contributes one or more [dev.kuml.plugin.api.renderer.KumlRendererPlugin] renderers. */
    RENDERER,

    /** Contributes one or more [dev.kuml.plugin.api.layout.KumlLayoutPlugin] layout engines. */
    LAYOUT,

    /** Contributes one or more [dev.kuml.plugin.api.codegen.KumlCodegenPlugin] code generators. */
    CODEGEN,

    /** Contributes one or more [dev.kuml.plugin.api.reverse.KumlReversePlugin] reverse engines. */
    REVERSE,
}
