package dev.kuml.plugin.examples.typescript

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.plugin.api.codegen.KumlCodegenPlugin
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion

/**
 * TypeScript Codegen Plugin — generates TypeScript interface/class skeletons
 * from UML class diagrams via [KumlCodegenPlugin] SPI.
 *
 * Permissions: [PluginPermission.FS_WRITE] (writes .ts files to output dir).
 */
public class TypeScriptCodegenPlugin : KumlCodegenPlugin {
    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = "dev.kuml.plugin.codegen.typescript",
            name = "TypeScript Code Generator",
            version = PluginVersion(1, 0, 0),
            kumlVersionRange = KumlVersionRange(">=0.12.0"),
            capabilities = setOf(PluginCapability.CODEGEN),
            requiredPermissions = setOf(PluginPermission.FS_WRITE),
        )

    override fun generators(): List<KumlCodeGenerator> = listOf(TypeScriptCodeGenerator())
}
