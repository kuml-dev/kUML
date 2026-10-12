package dev.kuml.desktop

import java.util.Properties

/**
 * The kUML Desktop version shown in Help ▸ About and reported by the MCP server's `serverInfo`.
 *
 * Source of truth is `dev/kuml/desktop/version.properties`, whose `${kumlVersion}` placeholder
 * Gradle expands from the project version at build time (see `jvmProcessResources` in
 * `build.gradle.kts`). The JAR manifest cannot serve here: the packaged app carries no
 * `Implementation-Version`, which is why this used to read "dev" outside a Gradle run.
 */
internal object AppVersion {
    private const val RESOURCE = "/dev/kuml/desktop/version.properties"
    private const val PLACEHOLDER_MARKER = "\${"
    const val UNKNOWN: String = "dev"

    /** Version from the expanded resource, else the manifest, else [UNKNOWN]. */
    val current: String by lazy {
        fromResource() ?: AppVersion::class.java.`package`?.implementationVersion ?: UNKNOWN
    }

    /** Extracts a usable version from [properties]; an unexpanded `${...}` placeholder is rejected. */
    internal fun parse(properties: Properties): String? =
        properties
            .getProperty("version")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && PLACEHOLDER_MARKER !in it }

    private fun fromResource(): String? =
        AppVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            parse(Properties().apply { load(stream) })
        }
}
