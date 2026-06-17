package dev.kuml.plugin.loader.registry

import kotlinx.serialization.Serializable

/**
 * Full parsed representation of `plugins/index.json`.
 */
@Serializable
public data class PluginRegistryIndex(
    val schemaVersion: Int = 1,
    val baseUrl: String = "https://plugins.kuml.dev",
    val plugins: List<PluginRegistryEntry> = emptyList(),
) {
    /** Find a plugin by its ID. */
    public fun find(id: String): PluginRegistryEntry? = plugins.firstOrNull { it.id == id }

    /** Filter by category. */
    public fun byCategory(category: String): List<PluginRegistryEntry> = plugins.filter { it.category == category }
}
