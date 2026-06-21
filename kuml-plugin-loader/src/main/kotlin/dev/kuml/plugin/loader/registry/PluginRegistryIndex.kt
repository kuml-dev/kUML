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

    /** Filter by category (exact match, case-sensitive). */
    public fun byCategory(category: String): List<PluginRegistryEntry> = plugins.filter { it.category == category }

    /**
     * Case-insensitive substring search across [PluginRegistryEntry.id], [PluginRegistryEntry.name]
     * and [PluginRegistryEntry.category].
     *
     * A blank or empty [query] returns all plugins.
     */
    public fun search(query: String): List<PluginRegistryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return plugins
        return plugins.filter {
            it.id.lowercase().contains(q) ||
                it.name.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }
    }
}
