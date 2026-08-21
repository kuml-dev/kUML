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
                throw KumlAiException.SettingsCorrupted(message = "Cannot parse settings file at $path: ${e.message}", cause = e)
            }
        val rawSchemaVersion = raw["schemaVersion"]?.jsonPrimitive?.int ?: 0
        return migrate(rawSchemaVersion = rawSchemaVersion, raw = raw)
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
                // V0 has no privacyMode, no schemaVersion — inject defaults, then fall through
                // to the v1 case instead of decoding directly here. Review fix: this used to
                // decode straight from schemaVersion=1 JSON, which skipped the v1→v2
                // LEGACY_V1_DEFAULT_SYSTEM_PROMPT cleanup below entirely — a V0 document ends
                // up on CURRENT_SCHEMA_VERSION=2 like every other document, not stuck at 1.
                val withDefaults =
                    JsonObject(
                        raw.toMutableMap().apply {
                            putIfAbsent("privacyMode", JsonPrimitive(true))
                            put("schemaVersion", JsonPrimitive(1))
                        },
                    )
                migrate(rawSchemaVersion = 1, raw = withDefaults)
            }
            1 -> {
                // V3.2.x — real tool-calling: DEFAULT_SYSTEM_PROMPT changed from a generic
                // one-liner to a real kUML-DSL-grammar-aware prompt. Schema v1 was already
                // released with `encodeDefaults = true`, so every prior save() wrote the OLD
                // default verbatim into ai-settings.json — a user who never customised the
                // prompt would otherwise never see the new one on load. Only drop the stored
                // value when it is exactly the old literal default; a genuinely customised
                // prompt is preserved untouched.
                val stored = raw["systemPrompt"]?.jsonPrimitive?.content
                val upgraded =
                    raw.toMutableMap().apply {
                        if (stored == null || stored == KumlAiSettings.LEGACY_V1_DEFAULT_SYSTEM_PROMPT) {
                            remove("systemPrompt") // let the current Kotlin default (new prompt) apply
                        }
                        put("schemaVersion", JsonPrimitive(2))
                    }
                json.decodeFromJsonElement(KumlAiSettings.serializer(), JsonObject(upgraded))
            }
            2 -> json.decodeFromJsonElement(KumlAiSettings.serializer(), raw)
            else -> throw KumlAiException.SettingsCorrupted(
                message =
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
