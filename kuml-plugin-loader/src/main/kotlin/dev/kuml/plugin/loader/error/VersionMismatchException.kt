package dev.kuml.plugin.loader.error

/**
 * Thrown when a plugin's declared [kumlVersionRange] excludes the current kUML runtime version.
 */
public class VersionMismatchException(
    public val pluginId: String,
    public val pluginVersionRange: String,
    public val runtimeVersion: String,
) : RuntimeException(
        "Plugin '$pluginId' requires kUML $pluginVersionRange but runtime is $runtimeVersion",
    )
