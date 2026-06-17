package dev.kuml.plugin.api.core

/**
 * Root marker for all kUML plugin types.
 *
 * Every plugin carries a [PluginDescriptor] that declares its identity,
 * version, compatibility range, capabilities, and required permissions.
 *
 * Concrete plugin types extend one of the category-specific sub-interfaces:
 * - [dev.kuml.plugin.api.theme.KumlThemePlugin]
 * - [dev.kuml.plugin.api.renderer.KumlRendererPlugin]
 * - [dev.kuml.plugin.api.layout.KumlLayoutPlugin]
 * - [dev.kuml.plugin.api.codegen.KumlCodegenPlugin]
 * - [dev.kuml.plugin.api.reverse.KumlReversePlugin]
 */
public interface KumlPlugin {
    /** Immutable metadata identifying this plugin. */
    public val descriptor: PluginDescriptor
}
