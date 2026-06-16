package dev.kuml.ai.vault

import dev.kuml.ai.KumlAiException
import dev.kuml.ai.settings.XdgPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Last-resort backend — stores secrets in plain JSON.
 * Always logs a WARNING on first use.
 *
 * This backend is always available (isAvailable returns true) but
 * provides no encryption. Users should be warned via the UI (V3.0.24).
 */
public class PlainJsonFallbackBackend(
    private val storagePath: Path = XdgPaths.plainSecretsPath(),
) : KeyVaultBackend {
    private val log = LoggerFactory.getLogger(PlainJsonFallbackBackend::class.java)

    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override val displayName: String get() = "Plain JSON (UNENCRYPTED)"

    /** Always available — plain JSON can always be written to the filesystem. */
    override fun isAvailable(): Boolean = true

    override fun put(
        key: String,
        secret: String,
    ) {
        warnOnUse()
        val map = readMap().toMutableMap()
        map[key] = JsonPrimitive(secret)
        writeMap(JsonObject(map))
    }

    override fun get(key: String): String? {
        warnOnUse()
        return readMap()[key]?.jsonPrimitive?.content
    }

    override fun delete(key: String) {
        val map = readMap().toMutableMap()
        if (map.remove(key) != null) {
            writeMap(JsonObject(map))
        }
    }

    private fun warnOnUse() {
        log.warn(
            "SECURITY WARNING: API keys are stored in PLAIN TEXT at {}. " +
                "This means anyone with filesystem access can read your API keys. " +
                "Set KUML_AI_PLAIN_OK=1 to suppress this warning. " +
                "Consider using a platform keystore for better security.",
            storagePath,
        )
    }

    private fun readMap(): JsonObject {
        if (!Files.exists(storagePath)) return JsonObject(emptyMap())
        return try {
            val text = Files.readString(storagePath, StandardCharsets.UTF_8)
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    private fun writeMap(data: JsonObject) {
        try {
            Files.createDirectories(storagePath.parent)
            val tmp = Files.createTempFile(storagePath.parent, "secrets", ".json.tmp")
            Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), data), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, storagePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            throw KumlAiException.VaultUnavailable(
                "Cannot write plain JSON secrets to $storagePath: ${e.message}",
                e,
            )
        }
    }
}
