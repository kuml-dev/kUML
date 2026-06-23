package dev.kuml.plugin.loader.registry

import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.loader.loader.LoadedPlugin

/**
 * Compares installed plugin versions against the remote registry index.
 *
 * Graceful degradation: if the registry is unreachable ([PluginRegistryException]),
 * returns an [UpdateCheckResult] with registryReachable=false and every latest=null —
 * never throws. Callers decide how loud to be about it.
 *
 * @param indexProvider injected for testing — fetches the registry index or throws
 *   [PluginRegistryException] on failure.
 * @param installedProvider injected so tests don't depend on the global [PluginRegistry] singleton.
 */
public class UpdateCheckService(
    private val indexProvider: () -> PluginRegistryIndex = { PluginRegistryClient().fetchIndex() },
    private val installedProvider: () -> List<LoadedPlugin> = { PluginRegistry.all() },
) {
    public fun check(): UpdateCheckResult {
        val installed = installedProvider()
        val index =
            try {
                indexProvider()
            } catch (e: PluginRegistryException) {
                return UpdateCheckResult(
                    plugins =
                        installed.map {
                            PluginUpdateInfo(
                                id = it.manifest.id,
                                installed = parseVersion(it.manifest.version),
                                latest = null,
                                registryEntry = null,
                                registryReachable = false,
                            )
                        },
                    registryReachable = false,
                    error = e.message,
                )
            }
        val infos =
            installed.map { lp ->
                val entry = index.find(lp.manifest.id)
                PluginUpdateInfo(
                    id = lp.manifest.id,
                    installed = parseVersion(lp.manifest.version),
                    latest = entry?.let { parseVersion(it.version) },
                    registryEntry = entry,
                )
            }
        return UpdateCheckResult(plugins = infos, registryReachable = true)
    }

    private fun parseVersion(v: String): PluginVersion = runCatching { PluginVersion.parse(v) }.getOrDefault(PluginVersion(0, 0, 0))
}
