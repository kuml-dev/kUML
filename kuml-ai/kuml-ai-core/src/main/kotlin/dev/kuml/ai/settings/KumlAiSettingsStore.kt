package dev.kuml.ai.settings

import dev.kuml.ai.KumlAiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** File-backed persistence and migration for KumlAiSettings. */
public class KumlAiSettingsStore(
    private val path: Path = XdgPaths.aiSettingsPath(),
    private val json: Json = DEFAULT_JSON,
) {
    /**
     * Read settings. Returns defaults when the file does not exist.
     * Throws [KumlAiException.SettingsCorrupted] on parse error.
     */
    public fun load(): KumlAiSettings {
        if (!Files.exists(path)) {
            return KumlAiSettings()
        }
        val raw: JsonObject =
            try {
                val text = Files.readString(path, StandardCharsets.UTF_8)
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw KumlAiException.SettingsCorrupted("Cannot parse settings file at $path: ${e.message}", e)
            }
        val rawSchemaVersion = raw["schemaVersion"]?.jsonPrimitive?.int ?: 0
        return migrate(rawSchemaVersion, raw)
    }

    /**
     * Atomic write via temp-file + Files.move(ATOMIC_MOVE, REPLACE_EXISTING).
     * Falls back to non-atomic move on Windows when ATOMIC_MOVE throws
     * AtomicMoveNotSupportedException.
     */
    public fun save(settings: KumlAiSettings) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, "ai-settings", ".json.tmp")
        try {
            Files.writeString(tmp, json.encodeToString(KumlAiSettings.serializer(), settings), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Windows NTFS: atomic move not always available — fall back
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    /**
     * Internal migration entry-point — called from [load] after reading raw JSON.
     * Migrates from [rawSchemaVersion] to the current schema version.
     */
    internal fun migrate(
        rawSchemaVersion: Int,
        raw: JsonObject,
    ): KumlAiSettings =
        when (rawSchemaVersion) {
            0 -> {
                // V0 has no privacyMode, no schemaVersion — inject defaults
                val withDefaults =
                    JsonObject(
                        raw.toMutableMap().apply {
                            putIfAbsent("privacyMode", JsonPrimitive(true))
                            put("schemaVersion", JsonPrimitive(1))
                        },
                    )
                json.decodeFromJsonElement(KumlAiSettings.serializer(), withDefaults)
            }
            1 -> json.decodeFromJsonElement(KumlAiSettings.serializer(), raw)
            else -> throw KumlAiException.SettingsCorrupted(
                "Unsupported schema version: $rawSchemaVersion " +
                    "(max known: ${KumlAiSettings.CURRENT_SCHEMA_VERSION})",
            )
        }

    public companion object {
        public val DEFAULT_JSON: Json =
            Json {
                prettyPrint = true
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
    }
}
