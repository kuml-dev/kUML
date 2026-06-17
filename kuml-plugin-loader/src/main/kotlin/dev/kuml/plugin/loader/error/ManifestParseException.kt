package dev.kuml.plugin.loader.error

/** Thrown when a `kuml-plugin.json` manifest cannot be parsed or fails validation. */
public class ManifestParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
