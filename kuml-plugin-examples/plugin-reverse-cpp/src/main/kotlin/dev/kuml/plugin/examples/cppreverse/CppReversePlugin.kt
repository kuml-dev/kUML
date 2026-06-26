package dev.kuml.plugin.examples.cppreverse

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.api.reverse.KumlReversePlugin

/**
 * C++ Reverse Plugin — parses C++ header and source files into UML models.
 *
 * Uses a handwritten recursive-descent structural parser.
 * Eclipse CDT Core is out of scope (OSGi/P2 only, not on Maven Central).
 *
 * Permission: [PluginPermission.FS_READ] for source directory access.
 *
 * CLI integration: `kuml reverse --lang cpp src/`
 * (requires `kuml-codegen-reverse-api` → `ReverseEngineRegistry` to pick this engine up)
 *
 * The [PluginDescriptor.id] is byte-identical to the `kuml-plugin.json` manifest id
 * `dev.kuml.plugins.reverse-cpp` so the plugin loader can match them.
 */
public class CppReversePlugin : KumlReversePlugin {
    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = "dev.kuml.plugins.reverse-cpp",
            name = "C++ Reverse Engine",
            version = PluginVersion(1, 0, 0),
            kumlVersionRange = KumlVersionRange(">=0.12.0"),
            capabilities = setOf(PluginCapability.REVERSE),
            requiredPermissions = setOf(PluginPermission.FS_READ),
        )

    override fun engines(): List<KumlReverseEngine> = listOf(CppReverseEngine())
}
