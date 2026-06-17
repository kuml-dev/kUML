package dev.kuml.plugin.loader.error

/** Thrown when a plugin JAR cannot be loaded, instantiated, or wired to a registry. */
public class PluginLoadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
