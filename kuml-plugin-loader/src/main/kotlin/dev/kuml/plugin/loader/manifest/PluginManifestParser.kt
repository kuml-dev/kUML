package dev.kuml.plugin.loader.manifest

import dev.kuml.plugin.loader.error.ManifestParseException
import kotlinx.serialization.json.Json

/**
 * Parses and validates `kuml-plugin.json` manifest files.
 *
 * Uses kotlinx.serialization. Unknown JSON keys are ignored for forward compatibility.
 */
public object PluginManifestParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            coerceInputValues = true
        }

    /**
     * Parse and validate a manifest from a JSON string.
     *
     * @throws ManifestParseException if the JSON is invalid or required fields are missing/invalid
     */
    public fun parse(jsonContent: String): PluginManifest {
        val manifest =
            try {
                json.decodeFromString<PluginManifest>(jsonContent)
            } catch (e: Exception) {
                throw ManifestParseException("Failed to parse kuml-plugin.json: ${e.message}", e)
            }
        validate(manifest)
        return manifest
    }

    private fun validate(m: PluginManifest) {
        if (m.schemaVersion != 1) {
            throw ManifestParseException("Unsupported schema version: ${m.schemaVersion}. Expected: 1")
        }
        if (m.id.isBlank()) throw ManifestParseException("Manifest 'id' must not be blank")
        if (m.name.isBlank()) throw ManifestParseException("Manifest 'name' must not be blank")
        if (m.version.isBlank()) throw ManifestParseException("Manifest 'version' must not be blank")
        if (m.kumlVersionRange.isBlank()) throw ManifestParseException("Manifest 'kumlVersionRange' must not be blank")
        if (m.extensions.isEmpty()) throw ManifestParseException("Manifest 'extensions' must not be empty")
        m.extensions.forEach { ext ->
            if (ext.category.isBlank()) {
                throw ManifestParseException("Extension entry 'category' must not be blank in plugin '${m.id}'")
            }
            if (ext.implementation.isBlank()) {
                throw ManifestParseException("Extension entry 'implementation' must not be blank in plugin '${m.id}'")
            }
            if (ext.id.isBlank()) {
                throw ManifestParseException("Extension entry 'id' must not be blank in plugin '${m.id}'")
            }
            if (ext.category !in VALID_CATEGORIES) {
                throw ManifestParseException(
                    "Unknown extension category '${ext.category}' in plugin '${m.id}'. Valid: $VALID_CATEGORIES",
                )
            }
        }
    }

    private val VALID_CATEGORIES = setOf("theme", "renderer", "layout", "codegen", "reverse")
}
