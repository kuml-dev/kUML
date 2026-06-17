package dev.kuml.plugin.examples.tsreverse

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.api.reverse.KumlReversePlugin

/**
 * TypeScript Reverse Plugin — parses TypeScript source files into UML models.
 *
 * V3.0.32 MVP: regex-based parsing of `interface`, `class`, and `enum` declarations.
 * Full TypeScript AST support via ts-morph (Node.js bridge) is planned for V3.1.
 *
 * Permission: [PluginPermission.FS_READ] for source directory access.
 *
 * CLI integration: `kuml reverse --lang typescript src/`
 * (requires `kuml-codegen-reverse-api` → `ReverseEngineRegistry` to pick this engine up)
 */
public class TypeScriptReversePlugin : KumlReversePlugin {
    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = "dev.kuml.plugin.reverse.typescript",
            name = "TypeScript Reverse Engine",
            version = PluginVersion(1, 0, 0),
            kumlVersionRange = KumlVersionRange(">=0.12.0"),
            capabilities = setOf(PluginCapability.REVERSE),
            requiredPermissions = setOf(PluginPermission.FS_READ),
        )

    override fun engines(): List<KumlReverseEngine> = listOf(TypeScriptReverseEngine())
}
