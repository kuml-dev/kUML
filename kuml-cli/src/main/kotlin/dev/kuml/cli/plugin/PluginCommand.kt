package dev.kuml.cli.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlVersion
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.loader.error.ManifestParseException
import dev.kuml.plugin.loader.error.PluginLoadException
import dev.kuml.plugin.loader.error.VersionMismatchException
import dev.kuml.plugin.loader.loader.PluginLoader
import dev.kuml.plugin.loader.manifest.PluginManifestParser
import dev.kuml.plugin.loader.registry.PluginRegistry
import dev.kuml.plugin.loader.scan.PluginScanPath
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The `kuml plugin` subcommand group.
 *
 * Sub-subcommands:
 * - `list`        — list installed plugins
 * - `install`     — install a plugin from a JAR path
 * - `remove`      — remove an installed plugin
 * - `info`        — show plugin details
 * - `permissions` — show plugin permissions
 * - `reload`      — reload all plugins from disk
 */
internal class PluginCommand : CliktCommand(name = "plugin") {
    init {
        subcommands(
            PluginListCommand(),
            PluginInstallCommand(),
            PluginRemoveCommand(),
            PluginInfoCommand(),
            PluginPermissionsCommand(),
            PluginReloadCommand(),
        )
    }

    override fun help(context: Context): String = "Manage kUML plugins (install, remove, list, info)."

    override fun run() = Unit
}

// ── `kuml plugin list` ────────────────────────────────────────────────────────

internal class PluginListCommand : CliktCommand(name = "list") {
    @Suppress("UnusedPrivateProperty")
    private val installed by option("--installed", help = "Show only installed plugins (default)").flag(default = true)

    override fun help(context: Context): String = "List installed kUML plugins."

    override fun run() {
        ensureLoaded()
        val plugins = PluginRegistry.all()
        if (plugins.isEmpty()) {
            echo("No plugins installed.")
            echo("Install plugins with: kuml plugin install <path-to-plugin.jar>")
            return
        }
        echo("Installed plugins (${plugins.size}):\n")
        for (p in plugins) {
            val m = p.manifest
            echo("  ${m.id}  v${m.version}")
            echo("    Name:        ${m.name}")
            echo("    Categories:  ${m.extensions.map { it.category }.distinct().joinToString(", ")}")
            if (m.maintainer.isNotBlank()) echo("    Maintainer:  ${m.maintainer}")
            echo("")
        }
    }
}

// ── `kuml plugin install` ─────────────────────────────────────────────────────

internal class PluginInstallCommand : CliktCommand(name = "install") {
    private val jarFile by argument(help = "Path to the plugin JAR file")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)

    private val force by option("--force", help = "Replace existing plugin with the same ID").flag()

    private val skipSignatureCheck by option(
        "--skip-signature-check",
        help = "Skip Ed25519 signature verification (unsafe, use only for local development JARs)",
    ).flag(default = false)

    override fun help(context: Context): String = "Install a kUML plugin from a JAR file. The JAR must contain a kuml-plugin.json manifest."

    override fun run() {
        val runtimeVersion = parseRuntimeVersion()

        // 1. Validate manifest before copying
        val manifestJson =
            readManifestFromJar(jarFile)
                ?: run {
                    System.err.println("Error: '${jarFile.name}' does not contain a kuml-plugin.json manifest.")
                    throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
                }

        val manifest =
            try {
                PluginManifestParser.parse(manifestJson)
            } catch (e: ManifestParseException) {
                System.err.println("Error: Invalid manifest in '${jarFile.name}': ${e.message}")
                throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
            }

        // 2. Version compatibility check
        if (!dev.kuml.plugin.api.core
                .KumlVersionRange(manifest.kumlVersionRange)
                .contains(runtimeVersion)
        ) {
            System.err.println(
                "Error: Plugin '${manifest.id}' requires kUML ${manifest.kumlVersionRange} " +
                    "but this is kUML $runtimeVersion.",
            )
            throw ProgramResult(ExitCodes.PLUGIN_VERSION_INCOMPATIBLE)
        }

        // 3. Signature check
        if (!skipSignatureCheck) {
            val sigContent =
                dev.kuml.plugin.loader.signature.PluginSignatureVerifier
                    .readSigFile(jarFile)
            if (sigContent != null) {
                val result =
                    dev.kuml.plugin.loader.signature.PluginSignatureVerifier.verify(
                        jarBytes = jarFile.readBytes(),
                        signatureBase64 = sigContent,
                        publicKeyBase64 = manifest.signature,
                    )
                when (result) {
                    is dev.kuml.plugin.loader.signature.SignatureVerificationResult.Invalid -> {
                        System.err.println("Warning: Signature verification FAILED: ${result.reason}")
                        System.err.println("Use --skip-signature-check to install anyway (not recommended).")
                        throw ProgramResult(ExitCodes.PLUGIN_SIGNATURE_INVALID)
                    }
                    is dev.kuml.plugin.loader.signature.SignatureVerificationResult.Valid ->
                        echo("Signature verified (key: ${result.publicKeyFingerprint})")
                    dev.kuml.plugin.loader.signature.SignatureVerificationResult.Unsigned ->
                        echo("Warning: Plugin is unsigned. Install with caution.")
                }
            } else {
                echo("Warning: No .sig file found — plugin is unsigned.")
            }
        }

        // 4. Check for existing plugin
        if (!force && PluginRegistry.get(manifest.id) != null) {
            System.err.println(
                "Plugin '${manifest.id}' is already installed. Use --force to replace it.",
            )
            throw ProgramResult(1)
        }

        // 5. Copy to user plugin dir
        val pluginDir = PluginScanPath.userPluginDir.toFile()
        pluginDir.mkdirs()
        val dest = File(pluginDir, jarFile.name)
        try {
            jarFile.copyTo(dest, overwrite = force)
        } catch (e: IOException) {
            System.err.println("Error copying plugin: ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }

        // 6. Load immediately
        try {
            PluginLoader.loadJar(dest, runtimeVersion)
        } catch (e: VersionMismatchException) {
            dest.delete()
            System.err.println("Version mismatch: ${e.message}")
            throw ProgramResult(ExitCodes.PLUGIN_VERSION_INCOMPATIBLE)
        } catch (e: PluginLoadException) {
            dest.delete()
            System.err.println("Failed to load plugin: ${e.message}")
            throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
        }

        echo("Installed: ${manifest.name} v${manifest.version} (${manifest.id})")
        if (manifest.permissions.isNotEmpty()) {
            echo("  Permissions: ${manifest.permissions.joinToString(", ")}")
        }
    }
}

// ── `kuml plugin remove` ──────────────────────────────────────────────────────

internal class PluginRemoveCommand : CliktCommand(name = "remove") {
    private val id by argument(help = "Plugin ID to remove (e.g. dev.kuml.plugin.pdv-theme)")

    override fun help(context: Context): String = "Remove an installed kUML plugin."

    override fun run() {
        ensureLoaded()
        PluginRegistry.get(id)
            ?: run {
                System.err.println("Plugin not found: '$id'")
                System.err.println("Installed plugins: ${PluginRegistry.all().map { it.manifest.id }}")
                throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
            }

        // Remove JAR from disk
        val pluginDir = PluginScanPath.userPluginDir.toFile()
        val candidates =
            pluginDir
                .listFiles { f -> f.extension == "jar" }
                ?.filter { jar -> manifestIdMatches(jar, id) }
                ?: emptyList()

        for (jar in candidates) {
            jar.delete()
        }

        PluginRegistry.unload(id)
        echo("Removed: $id")
    }

    private fun manifestIdMatches(
        jar: File,
        targetId: String,
    ): Boolean =
        runCatching {
            val json = readManifestFromJar(jar) ?: return false
            PluginManifestParser.parse(json).id == targetId
        }.getOrDefault(false)
}

// ── `kuml plugin info` ────────────────────────────────────────────────────────

internal class PluginInfoCommand : CliktCommand(name = "info") {
    private val id by argument(help = "Plugin ID")

    override fun help(context: Context): String = "Show details of an installed plugin."

    override fun run() {
        ensureLoaded()
        val loaded =
            PluginRegistry.get(id)
                ?: run {
                    System.err.println("Plugin not found: '$id'")
                    throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
                }

        val m = loaded.manifest
        echo("Plugin: ${m.name}")
        echo("  ID:           ${m.id}")
        echo("  Version:      ${m.version}")
        echo("  kUML range:   ${m.kumlVersionRange}")
        echo("  License:      ${m.licenseSpdx}")
        if (m.maintainer.isNotBlank()) echo("  Maintainer:   ${m.maintainer}")
        if (m.homepage.isNotBlank()) echo("  Homepage:     ${m.homepage}")
        echo("  Extensions:")
        for (ext in m.extensions) {
            echo("    [${ext.category}] ${ext.id} -> ${ext.implementation}")
        }
        if (m.permissions.isNotEmpty()) {
            echo("  Permissions:  ${m.permissions.joinToString(", ")}")
        } else {
            echo("  Permissions:  none")
        }
        val rawSig = m.signature
        val sigStatus =
            when {
                rawSig != null -> {
                    val fp =
                        dev.kuml.plugin.loader.signature.PluginSignatureVerifier
                            .publicKeyFingerprint(rawSig)
                    "signed (fingerprint: ${fp ?: "unknown"})"
                }
                else -> "unsigned"
            }
        echo("  Signature:    $sigStatus")
    }
}

// ── `kuml plugin permissions` ─────────────────────────────────────────────────

internal class PluginPermissionsCommand : CliktCommand(name = "permissions") {
    private val id by argument(help = "Plugin ID")

    override fun help(context: Context): String =
        "Show permissions declared by a plugin. Codegen/Reverse plugins require explicit permissions for file system access."

    override fun run() {
        ensureLoaded()
        val loaded =
            PluginRegistry.get(id)
                ?: run {
                    System.err.println("Plugin not found: '$id'")
                    throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
                }

        val m = loaded.manifest
        echo("Permissions for ${m.id} v${m.version}:\n")

        if (m.permissions.isEmpty()) {
            echo("  No permissions declared.")
            echo("  (Theme, Renderer, and Layout plugins typically need no permissions.)")
            return
        }

        for (perm in m.permissions) {
            val description = PERMISSION_DESCRIPTIONS[perm] ?: perm
            val risk =
                when {
                    perm.startsWith("process.exec") -> " [HIGH RISK]"
                    perm.startsWith("network.") -> " [NETWORK]"
                    else -> ""
                }
            echo("  $perm$risk")
            echo("    $description")
            echo("")
        }
    }

    private companion object {
        val PERMISSION_DESCRIPTIONS =
            mapOf(
                "render.read-resources" to "May read bundled classpath resources (themes, icons).",
                "fs.read" to "May read source files from the declared input path.",
                "fs.write" to "May write generated files to the declared output directory.",
                "network.http" to "May make outbound HTTP requests to declared host patterns.",
                "process.exec" to "May invoke external processes. Requires explicit user confirmation.",
            )
    }
}

// ── `kuml plugin reload` ──────────────────────────────────────────────────────

internal class PluginReloadCommand : CliktCommand(name = "reload") {
    override fun help(context: Context): String = "Reload all plugins from disk (development workflow)."

    override fun run() {
        val runtimeVersion = parseRuntimeVersion()
        PluginLoader.reload(runtimeVersion)
        val count = PluginRegistry.all().size
        echo("Plugins reloaded. $count plugin(s) loaded.")
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

private const val MANIFEST_ENTRY = "kuml-plugin.json"

private fun readManifestFromJar(jar: File): String? =
    runCatching {
        ZipFile(jar).use { zip ->
            zip.getEntry(MANIFEST_ENTRY)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().readText()
            }
        }
    }.getOrNull()

private fun ensureLoaded() {
    if (PluginRegistry.all().isEmpty()) {
        PluginLoader.load(parseRuntimeVersion())
    }
}

private fun parseRuntimeVersion(): PluginVersion =
    runCatching { PluginVersion.parse(KumlVersion.version) }
        .getOrDefault(PluginVersion(0, 12, 0))
