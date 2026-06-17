package dev.kuml.plugin.api.codegen

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.plugin.api.core.KumlPlugin

/**
 * Plugin SPI for model-to-text code generators.
 *
 * A codegen plugin contributes one or more [KumlCodeGenerator] instances.
 * Built-in generators (Kotlin, Java, SQL) implement this interface.
 *
 * Requires [dev.kuml.plugin.api.core.PluginPermission.FS_READ] and
 * [dev.kuml.plugin.api.core.PluginPermission.FS_WRITE] — enforced by
 * the Sandbox (V3.0.29).
 */
public interface KumlCodegenPlugin : KumlPlugin {
    /** All code generators contributed by this plugin. Must not be empty. */
    public fun generators(): List<KumlCodeGenerator>
}
