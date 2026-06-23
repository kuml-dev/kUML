package dev.kuml.plugin.loader.registry

import dev.kuml.plugin.api.core.PluginVersion

/** Update status for a single installed plugin. */
public data class PluginUpdateInfo(
    val id: String,
    val installed: PluginVersion,
    val latest: PluginVersion?,
    val registryEntry: PluginRegistryEntry?,
    /** Whether the registry was reachable when this info was produced. */
    val registryReachable: Boolean = true,
) {
    val updateAvailable: Boolean
        get() = latest != null && latest > installed

    val status: PluginUpdateState
        get() =
            when {
                latest == null && !registryReachable -> PluginUpdateState.REGISTRY_UNREACHABLE
                latest == null -> PluginUpdateState.NOT_IN_REGISTRY
                latest > installed -> PluginUpdateState.UPDATE_AVAILABLE
                else -> PluginUpdateState.UP_TO_DATE
            }
}

public enum class PluginUpdateState {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    NOT_IN_REGISTRY,

    /** The registry could not be reached; the plugin's update status is unknown. */
    REGISTRY_UNREACHABLE,
}

/**
 * Outcome of a full update check.
 *
 * [registryReachable] = false means graceful degradation: [plugins] still lists
 * every installed plugin, but each [PluginUpdateInfo.latest] is null.
 */
public data class UpdateCheckResult(
    val plugins: List<PluginUpdateInfo>,
    val registryReachable: Boolean,
    val error: String? = null,
) {
    val updateCount: Int get() = plugins.count { it.updateAvailable }
    val hasUpdates: Boolean get() = updateCount > 0
}
