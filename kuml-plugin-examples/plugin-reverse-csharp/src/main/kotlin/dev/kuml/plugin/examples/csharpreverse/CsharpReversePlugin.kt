package dev.kuml.plugin.examples.csharpreverse

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.api.reverse.KumlReversePlugin

/**
 * C# Reverse Plugin — parses C# source files into UML models (V3.1.40).
 *
 * Uses a handwritten recursive-descent structural parser.
 * ANTLR4 was evaluated but rejected — no reliable ANTLR4 C# grammar artifact
 * is available on Maven Central. Option B (structural handwritten parsing)
 * mirrors the approach proven in V3.1.39 for C++.
 *
 * Permission: [PluginPermission.FS_READ] for source directory access.
 *
 * CLI integration: `kuml reverse --lang csharp src/`
 *
 * The [PluginDescriptor.id] is byte-identical to the `kuml-plugin.json` manifest id
 * `dev.kuml.plugins.reverse-csharp` so the plugin loader can match them.
 */
public class CsharpReversePlugin : KumlReversePlugin {
    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = "dev.kuml.plugins.reverse-csharp",
            name = "C# Reverse Engine",
            version = PluginVersion(1, 0, 0),
            kumlVersionRange = KumlVersionRange(">=0.12.0"),
            capabilities = setOf(PluginCapability.REVERSE),
            requiredPermissions = setOf(PluginPermission.FS_READ),
        )

    override fun engines(): List<KumlReverseEngine> = listOf(CsharpReverseEngine())
}
