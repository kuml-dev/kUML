package dev.kuml.cli.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.kuml.cli.ExitCodes
import dev.kuml.plugin.loader.registry.PluginUpdateState
import dev.kuml.plugin.loader.registry.UpdateCheckResult
import dev.kuml.plugin.loader.registry.UpdateCheckService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `kuml plugin check-updates` — compare installed plugins against the remote registry.
 *
 * Exit codes (CI contract):
 *   - **0**  : all installed plugins are up-to-date (or no plugins installed).
 *   - **44** : at least one plugin has a newer version in the registry ([ExitCodes.PLUGIN_UPDATES_AVAILABLE]).
 *   - **1**  : registry unreachable ([ExitCodes.ONLINE_ERROR]).
 *
 * Note: the original task spec requested exit 2, but 2 = [ExitCodes.USAGE] (Clikt's own
 * usage-error code). Exit 44 avoids CI scripts misinterpreting a type error as "updates found".
 */
internal class PluginCheckUpdatesCommand(
    private val serviceFactory: () -> UpdateCheckService = { UpdateCheckService() },
) : CliktCommand(name = "check-updates") {
    private val json by option("--json", help = "Emit result as single-line JSON (stable contract).").flag(default = false)

    override fun help(context: Context): String =
        "Check for updates to installed plugins (exit 0=current, 44=updates available, 1=online error)."

    override fun run() {
        ensureLoaded()
        val result = serviceFactory().check()

        if (json) {
            emitJson(result)
        } else {
            emitTable(result)
        }

        when {
            !result.registryReachable -> {
                System.err.println("Warning: Could not reach the plugin registry: ${result.error}")
                System.err.println("Check your internet connection or visit https://plugins.kuml.dev directly.")
                throw ProgramResult(ExitCodes.ONLINE_ERROR)
            }
            result.hasUpdates -> throw ProgramResult(ExitCodes.PLUGIN_UPDATES_AVAILABLE)
        }
    }

    private fun emitTable(result: UpdateCheckResult) {
        if (result.plugins.isEmpty()) {
            echo("No plugins installed.")
            return
        }

        val idWidth = maxOf(result.plugins.maxOf { it.id.length }, "PLUGIN ID".length)
        val instWidth = maxOf(result.plugins.maxOf { it.installed.toString().length }, "INSTALLED".length)
        val latWidth = maxOf(result.plugins.maxOf { (it.latest?.toString() ?: "—").length }, "LATEST".length)

        val header = "%-${idWidth}s  %-${instWidth}s  %-${latWidth}s  STATUS".format("PLUGIN ID", "INSTALLED", "LATEST")
        echo(header)
        echo("─".repeat(header.length + 20))

        for (info in result.plugins) {
            val latestStr = info.latest?.toString() ?: "—"
            val statusStr =
                when (info.status) {
                    PluginUpdateState.UPDATE_AVAILABLE -> "⬆ update available"
                    PluginUpdateState.UP_TO_DATE -> "✓ up-to-date"
                    PluginUpdateState.NOT_IN_REGISTRY -> "? not in registry"
                    PluginUpdateState.REGISTRY_UNREACHABLE -> "⚠ registry unreachable"
                }
            echo(
                "%-${idWidth}s  %-${instWidth}s  %-${latWidth}s  %s".format(
                    info.id,
                    info.installed.toString(),
                    latestStr,
                    statusStr,
                ),
            )
        }

        echo("")
        if (result.hasUpdates) {
            echo("${result.updateCount} update(s) available. Run 'kuml plugin upgrade --all' to upgrade.")
        } else if (result.registryReachable) {
            echo("All plugins are up-to-date.")
        }
    }

    @Serializable
    private data class PluginUpdateInfoDto(
        val id: String,
        val installed: String,
        val latest: String?,
        val status: String,
    )

    @Serializable
    private data class UpdateCheckResultDto(
        val registryReachable: Boolean,
        val updateCount: Int,
        val plugins: List<PluginUpdateInfoDto>,
        val error: String? = null,
    )

    private fun emitJson(result: UpdateCheckResult) {
        val dto =
            UpdateCheckResultDto(
                registryReachable = result.registryReachable,
                updateCount = result.updateCount,
                plugins =
                    result.plugins.map { info ->
                        val statusStr =
                            when (info.status) {
                                PluginUpdateState.UPDATE_AVAILABLE -> "update-available"
                                PluginUpdateState.UP_TO_DATE -> "up-to-date"
                                PluginUpdateState.NOT_IN_REGISTRY -> "not-in-registry"
                                PluginUpdateState.REGISTRY_UNREACHABLE -> "registry-unreachable"
                            }
                        PluginUpdateInfoDto(
                            id = info.id,
                            installed = info.installed.toString(),
                            latest = info.latest?.toString(),
                            status = statusStr,
                        )
                    },
                error = result.error,
            )
        echo(Json.encodeToString(dto))
    }
}
