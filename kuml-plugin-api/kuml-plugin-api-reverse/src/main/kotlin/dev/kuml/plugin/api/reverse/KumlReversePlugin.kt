package dev.kuml.plugin.api.reverse

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.plugin.api.core.KumlPlugin

/**
 * Plugin SPI for source-to-model reverse engineering engines.
 *
 * A reverse plugin contributes one or more [KumlReverseEngine] instances.
 * Built-in Java (JavaParser) and Kotlin (PSI) engines implement this interface.
 *
 * Requires [dev.kuml.plugin.api.core.PluginPermission.FS_READ] for source tree access.
 */
public interface KumlReversePlugin : KumlPlugin {
    /** All reverse engines contributed by this plugin. Must not be empty. */
    public fun engines(): List<KumlReverseEngine>
}
