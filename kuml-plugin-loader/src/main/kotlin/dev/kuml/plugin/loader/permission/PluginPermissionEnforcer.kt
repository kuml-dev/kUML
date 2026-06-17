package dev.kuml.plugin.loader.permission

import dev.kuml.plugin.api.core.KumlPlugin
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.loader.error.PluginPermissionDeniedException

/**
 * Enforces plugin permission declarations before sensitive operations.
 *
 * Theme, Renderer, and Layout plugins run outside the permission check
 * (pure computation, no I/O). Codegen and Reverse plugins require
 * explicit [PluginPermission] declarations.
 *
 * V3.0.29: static enforcement only. Dynamic sandbox integration (blocking
 * actual file-system calls) is deferred to a future version.
 */
public object PluginPermissionEnforcer {
    /**
     * Verifies that [plugin] has declared [permission].
     *
     * @throws PluginPermissionDeniedException if the permission is not declared
     */
    @Throws(PluginPermissionDeniedException::class)
    public fun require(
        plugin: KumlPlugin,
        permission: PluginPermission,
    ) {
        if (permission !in plugin.descriptor.requiredPermissions) {
            throw PluginPermissionDeniedException(plugin.descriptor.id, permission)
        }
    }

    /**
     * Returns true if [plugin] has declared [permission].
     */
    public fun has(
        plugin: KumlPlugin,
        permission: PluginPermission,
    ): Boolean = permission in plugin.descriptor.requiredPermissions

    /**
     * Wraps [block] with a permission check.
     * [block] is only invoked if [plugin] has declared [permission].
     */
    public fun <T> withPermission(
        plugin: KumlPlugin,
        permission: PluginPermission,
        block: () -> T,
    ): T {
        require(plugin, permission)
        return block()
    }
}
