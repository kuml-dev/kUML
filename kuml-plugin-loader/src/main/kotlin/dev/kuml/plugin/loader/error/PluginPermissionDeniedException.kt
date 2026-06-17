package dev.kuml.plugin.loader.error

import dev.kuml.plugin.api.core.PluginPermission

/** Thrown when a plugin attempts an operation it has not declared permission for. */
public class PluginPermissionDeniedException(
    public val pluginId: String,
    public val requiredPermission: PluginPermission,
) : RuntimeException(
        "Plugin '$pluginId' requires permission $requiredPermission but it is not declared in its manifest",
    )
