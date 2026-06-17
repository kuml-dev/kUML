package dev.kuml.plugin.loader.manifest

import kotlinx.serialization.Serializable

/**
 * One extension point declared in `kuml-plugin.json`.
 *
 * @param category       SPI category: "theme", "renderer", "layout", "codegen", "reverse"
 * @param implementation Fully-qualified class name of the [dev.kuml.plugin.api.core.KumlPlugin] implementation
 * @param id             Stable identifier for this extension (unique within the plugin)
 */
@Serializable
public data class ExtensionEntry(
    val category: String,
    val implementation: String,
    val id: String,
)
