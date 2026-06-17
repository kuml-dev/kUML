package dev.kuml.plugin.loader.loader

import dev.kuml.plugin.api.core.KumlPlugin
import dev.kuml.plugin.loader.manifest.PluginManifest

/**
 * Runtime record of a successfully loaded plugin.
 *
 * @param manifest    The parsed `kuml-plugin.json`
 * @param plugins     All instantiated [KumlPlugin] implementations (one per extension entry)
 * @param classLoader The per-plugin [PluginClassLoader], or `null` for built-in classpath plugins
 */
public data class LoadedPlugin(
    val manifest: PluginManifest,
    val plugins: List<KumlPlugin>,
    val classLoader: PluginClassLoader?,
)
