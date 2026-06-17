package dev.kuml.plugin.loader.registry

import dev.kuml.plugin.loader.loader.LoadedPlugin

/**
 * Central runtime index of all loaded kUML plugins.
 *
 * Thread-safe: all mutations are synchronized. Read paths ([get], [all])
 * return snapshot copies.
 */
public object PluginRegistry {
    private val loaded = mutableMapOf<String, LoadedPlugin>()

    /** Register a loaded plugin. Replaces any existing entry with the same manifest id. */
    @Synchronized
    public fun register(plugin: LoadedPlugin) {
        loaded[plugin.manifest.id] = plugin
    }

    /** Retrieve a loaded plugin by its manifest id. */
    @Synchronized
    public fun get(id: String): LoadedPlugin? = loaded[id]

    /** All currently loaded plugins (snapshot). */
    @Synchronized
    public fun all(): List<LoadedPlugin> = loaded.values.toList()

    /** Unload a plugin: removes from the index and closes its class loader. */
    @Synchronized
    public fun unload(id: String): Boolean {
        val entry = loaded.remove(id) ?: return false
        entry.classLoader?.close()
        return true
    }

    /** Clear all loaded plugins (use in tests only). */
    @Synchronized
    public fun clearForTest() {
        loaded.clear()
    }
}
