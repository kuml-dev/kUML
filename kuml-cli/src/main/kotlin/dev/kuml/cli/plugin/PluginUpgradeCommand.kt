package dev.kuml.cli.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.kuml.cli.ExitCodes
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.loader.error.ManifestParseException
import dev.kuml.plugin.loader.error.PluginLoadException
import dev.kuml.plugin.loader.error.VersionMismatchException
import dev.kuml.plugin.loader.loader.PluginLoader
import dev.kuml.plugin.loader.manifest.PluginManifest
import dev.kuml.plugin.loader.manifest.PluginManifestParser
import dev.kuml.plugin.loader.registry.DownloadedPlugin
import dev.kuml.plugin.loader.registry.PluginDownloader
import dev.kuml.plugin.loader.registry.PluginRegistry
import dev.kuml.plugin.loader.registry.PluginRegistryException
import dev.kuml.plugin.loader.registry.PluginSigningKey
import dev.kuml.plugin.loader.registry.PluginUpdateInfo
import dev.kuml.plugin.loader.registry.UpdateCheckResult
import dev.kuml.plugin.loader.registry.UpdateCheckService
import dev.kuml.plugin.loader.scan.PluginScanPath
import dev.kuml.plugin.loader.signature.PluginSignatureVerifier
import dev.kuml.plugin.loader.signature.SignatureVerificationResult
import java.io.File
import java.io.IOException

/**
 * `kuml plugin upgrade [id] [--all]` — upgrade installed plugins to the latest registry version.
 *
 * Downloads the new JAR from the registry `downloads` URL, verifies the Ed25519 signature
 * (using the registry's `signaturePublicKey` as the trust anchor), checks for new permissions,
 * unloads the old plugin, replaces the JAR, and loads the new version.
 *
 * New permissions gate: if the new version declares permissions not present in the old version,
 * the user is prompted to confirm (unless `--yes` is passed).
 */
internal class PluginUpgradeCommand(
    private val serviceFactory: () -> UpdateCheckService = { UpdateCheckService() },
    private val downloaderFactory: () -> PluginDownloader = { PluginDownloader() },
) : CliktCommand(name = "upgrade") {
    private val id by argument(help = "Plugin ID to upgrade. Omit together with --all to upgrade all.").optional()

    private val all by option("--all", help = "Upgrade every installed plugin that has an update.").flag()

    private val skipSignatureCheck by option(
        "--skip-signature-check",
        help = "Skip Ed25519 signature verification (unsafe; use only for local development).",
    ).flag(default = false)

    private val assumeYes by option(
        "--yes",
        "-y",
        help = "Auto-confirm new permissions without prompting.",
    ).flag(default = false)

    private val force by option(
        "--force",
        help = "Replace the JAR even if the registry reports the same version.",
    ).flag(default = false)

    override fun help(context: Context): String =
        "Upgrade installed plugins to the latest version from the registry. " +
            "Exits with code 45 (PLUGIN_UPGRADE_FAILED) when --all is used and at least one plugin fails to upgrade."

    override fun run() {
        if (id == null && !all) {
            System.err.println("Error: Provide a plugin ID or pass --all to upgrade all plugins.")
            throw ProgramResult(ExitCodes.USAGE)
        }
        if (id != null && all) {
            System.err.println("Error: Cannot use both a plugin ID and --all at the same time.")
            throw ProgramResult(ExitCodes.USAGE)
        }

        ensureLoaded()
        val result = serviceFactory().check()

        if (!result.registryReachable) {
            System.err.println("Error: Could not reach the plugin registry: ${result.error}")
            System.err.println("Check your internet connection or visit https://plugins.kuml.dev directly.")
            throw ProgramResult(ExitCodes.ONLINE_ERROR)
        }

        val targets = resolveTargets(result)
        if (targets.isEmpty()) {
            echo("All specified plugins are already up-to-date.")
            return
        }

        val runtimeVersion = parseRuntimeVersion()
        val downloader = downloaderFactory()
        var upgraded = 0
        var failed = 0

        for (info in targets) {
            try {
                upgradeOne(info, downloader, runtimeVersion)
                upgraded++
            } catch (e: PluginUpgradeException) {
                System.err.println("Failed to upgrade ${info.id}: ${e.message}")
                failed++
            }
        }

        echo("")
        echo("Upgrade complete: $upgraded upgraded${if (failed > 0) ", $failed failed" else ""}.")
        if (failed > 0) throw ProgramResult(ExitCodes.PLUGIN_UPGRADE_FAILED)
    }

    private fun resolveTargets(result: UpdateCheckResult): List<PluginUpdateInfo> {
        if (all) {
            return result.plugins.filter { it.updateAvailable }
        }

        val pluginId = id!!
        val info = result.plugins.firstOrNull { it.id == pluginId }

        if (info == null) {
            // Not installed at all
            System.err.println("Plugin not installed: '$pluginId'")
            System.err.println("Install it first with: kuml plugin install <path-to-plugin.jar>")
            throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
        }

        if (info.latest == null) {
            System.err.println("Plugin '$pluginId' is not found in the registry.")
            throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
        }

        if (!info.updateAvailable && !force) {
            echo("Plugin '$pluginId' is already up-to-date (v${info.installed}).")
            return emptyList()
        }

        return listOf(info)
    }

    private fun upgradeOne(
        info: PluginUpdateInfo,
        downloader: PluginDownloader,
        runtimeVersion: PluginVersion,
    ) {
        val entry = info.registryEntry ?: throw PluginUpgradeException("No registry entry for ${info.id}")

        echo("Upgrading ${info.id}: v${info.installed} → v${info.latest} …")

        // 1. Download JAR (and optional .sig)
        val downloaded: DownloadedPlugin =
            try {
                downloader.download(entry.downloads)
            } catch (e: PluginRegistryException) {
                throw PluginUpgradeException("Download failed: ${e.message}", e)
            }

        try {
            // 2. Parse new manifest
            val newManifestJson =
                readManifestFromJar(downloaded.jar.toFile())
                    ?: throw PluginUpgradeException("Downloaded JAR has no kuml-plugin.json manifest")

            val newManifest: PluginManifest =
                try {
                    PluginManifestParser.parse(newManifestJson)
                } catch (e: ManifestParseException) {
                    throw PluginUpgradeException("Invalid manifest in downloaded JAR: ${e.message}", e)
                }

            // 3. Version compatibility check
            if (!KumlVersionRange(newManifest.kumlVersionRange).contains(runtimeVersion)) {
                throw PluginUpgradeException(
                    "New version ${newManifest.version} of '${info.id}' requires " +
                        "kUML ${newManifest.kumlVersionRange} but this is kUML $runtimeVersion",
                )
            }

            // 4. Signature check
            if (skipSignatureCheck) {
                echo(
                    "WARNING: Signature verification skipped for '${info.id}'. " +
                        "Only do this for trusted local/development builds.",
                    err = true,
                )
            } else {
                verifySignature(downloaded, newManifest, entry.signingKeys)
            }

            // 5. New-permission consent gate
            val oldPermissions = PluginRegistry.get(info.id)?.manifest?.permissions ?: emptyList()
            val newPermissions = newManifest.permissions - oldPermissions.toSet()
            if (newPermissions.isNotEmpty() && !assumeYes) {
                echo("")
                echo("  Plugin '${info.id}' v${newManifest.version} requests NEW permissions:")
                newPermissions.forEach { echo("    + $it") }
                echo("")
                if (!promptYesNo("Grant new permissions and continue upgrade? [y/N] ")) {
                    echo("Upgrade of '${info.id}' aborted (new permissions declined).")
                    return
                }
            }

            // 6. Unload old plugin (releases file handle on Windows)
            PluginRegistry.unload(info.id)

            // 7. Remove old JAR(s) from user plugin dir
            val pluginDir = PluginScanPath.userPluginDir.toFile()
            val oldJars =
                pluginDir
                    .listFiles { f -> f.extension == "jar" }
                    ?.filter { jar -> manifestIdMatchesForUpgrade(jar, info.id) }
                    ?: emptyList()
            for (jar in oldJars) jar.delete()

            // 8. Copy new JAR into user plugin dir
            pluginDir.mkdirs()
            val destName = downloaded.jar.fileName.toString()
            val dest = File(pluginDir, destName)
            try {
                downloaded.jar.toFile().copyTo(dest, overwrite = true)
            } catch (e: IOException) {
                throw PluginUpgradeException("Failed to copy new JAR: ${e.message}", e)
            }

            // 9. Load new version
            try {
                PluginLoader.loadJar(dest, runtimeVersion)
            } catch (e: VersionMismatchException) {
                dest.delete()
                throw PluginUpgradeException("Version mismatch after copy: ${e.message}", e)
            } catch (e: PluginLoadException) {
                dest.delete()
                throw PluginUpgradeException("Failed to load new plugin: ${e.message}", e)
            }

            echo("  Upgraded to v${newManifest.version} (${info.id})")
            if (newManifest.permissions.isNotEmpty()) {
                echo("  Permissions: ${newManifest.permissions.joinToString(", ")}")
            }
        } finally {
            // Temp-file hygiene — delete downloaded JAR on failure paths
            runCatching { downloaded.jar.toFile().delete() }
            runCatching {
                downloaded.jar.parent
                    ?.toFile()
                    ?.delete()
            }
        }
    }

    private fun verifySignature(
        downloaded: DownloadedPlugin,
        manifest: PluginManifest,
        signingKeys: List<PluginSigningKey>,
    ) {
        // For registry-download upgrades the trust anchor MUST be the registry's signingKeys list.
        // Falling back to manifest.signature (the self-declared key embedded in the downloaded JAR)
        // would allow a malicious actor to supply their own key and a matching self-signed .sig,
        // making signature verification meaningless.
        val activeKeys = signingKeys.filter { it.isUsable() }
        if (activeKeys.isEmpty()) {
            throw PluginUpgradeException(
                "Registry entry for '${manifest.id}' has no active signing keys — " +
                    "cannot verify authenticity. Use --skip-signature-check to bypass " +
                    "(not recommended).",
            )
        }
        val sigContent = downloaded.sig

        if (sigContent != null) {
            val result =
                PluginSignatureVerifier.verifyWithKeys(
                    jarBytes = downloaded.jar.toFile().readBytes(),
                    signatureBase64 = sigContent,
                    signingKeys = signingKeys,
                )
            when (result) {
                is SignatureVerificationResult.Invalid ->
                    throw PluginUpgradeException(
                        "Signature verification FAILED for '${manifest.id}': ${result.reason}. " +
                            "Use --skip-signature-check to install anyway (not recommended).",
                    )
                is SignatureVerificationResult.Valid ->
                    echo("  Signature verified (key: ${result.keyId ?: result.publicKeyFingerprint})")
                SignatureVerificationResult.Unsigned ->
                    echo("  Warning: No signature present — install with caution.")
            }
        } else {
            echo("  Warning: No .sig file found — plugin is unsigned.")
        }
    }

    private fun promptYesNo(prompt: String): Boolean {
        print(prompt)
        val answer = readlnOrNull()?.trim()?.lowercase() ?: ""
        return answer == "y" || answer == "yes"
    }

    private fun manifestIdMatchesForUpgrade(
        jar: File,
        targetId: String,
    ): Boolean =
        runCatching {
            val json = readManifestFromJar(jar) ?: return false
            PluginManifestParser.parse(json).id == targetId
        }.getOrDefault(false)
}

/** Internal exception for upgrade failure paths — caught and reported per-plugin. */
private class PluginUpgradeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
