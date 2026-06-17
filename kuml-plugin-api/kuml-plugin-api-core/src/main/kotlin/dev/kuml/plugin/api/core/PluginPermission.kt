package dev.kuml.plugin.api.core

import kotlinx.serialization.Serializable

/**
 * I/O permissions a plugin must declare to access resources outside
 * pure computation. Enforced by the Sandbox (V3.0.29).
 *
 * Theme, Renderer, and Layout plugins rarely need permissions.
 * Codegen and Reverse plugins typically need [FS_READ] and [FS_WRITE].
 */
@Serializable
public enum class PluginPermission {
    /** May read bundled classpath resources (implicit for all plugins). */
    RENDER_READ_RESOURCES,

    /** May read files from the source tree passed in the request. */
    FS_READ,

    /** May write files to the output directory declared in the request. */
    FS_WRITE,

    /** May make outbound HTTP requests to declared host patterns. */
    NETWORK_HTTP,

    /** May invoke external processes (requires explicit user confirmation). */
    PROCESS_EXEC,
}
