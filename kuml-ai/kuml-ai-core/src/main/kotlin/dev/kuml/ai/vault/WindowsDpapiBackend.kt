package dev.kuml.ai.vault

import dev.kuml.ai.KumlAiException
import dev.kuml.ai.settings.XdgPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Uses JNA-bound `Crypt32.dll` (CryptProtectData / CryptUnprotectData) for Windows.
 *
 * DPAPI encrypts per-user: the encrypted bytes are only decryptable by the same
 * Windows user account on the same machine. The encrypted bytes are Base64-encoded
 * and stored in a JSON map at [storagePath].
 *
 * Note: This backend is only functional on Windows. On other platforms
 * isAvailable() returns false and no JNA calls are made.
 */
public class WindowsDpapiBackend(
    private val storagePath: Path = XdgPaths.kumlConfigDir().resolve("secrets.dpapi"),
) : KeyVaultBackend {
    override val displayName: String get() = "Windows DPAPI"

    override fun isAvailable(): Boolean {
        if (OsDetection.current() != OsDetection.Os.WINDOWS) return false
        return try {
            Class.forName("com.sun.jna.platform.win32.Crypt32Util")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    override fun put(
        key: String,
        secret: String,
    ) {
        val crypt32 = loadCrypt32()
        val protectedBytes = crypt32.protect(secret.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(protectedBytes)
        val map = readMap().toMutableMap()
        map[key] = JsonPrimitive(encoded)
        writeMap(JsonObject(map))
    }

    override fun get(key: String): String? {
        val encoded = readMap()[key]?.jsonPrimitive?.content ?: return null
        return try {
            val crypt32 = loadCrypt32()
            val decrypted = crypt32.unprotect(Base64.getDecoder().decode(encoded))
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw KumlAiException.VaultUnavailable(
                message = "Windows DPAPI decryption failed for key '$key': ${e.message}",
                cause = e,
            )
        }
    }

    override fun delete(key: String) {
        val map = readMap().toMutableMap()
        if (map.remove(key) != null) {
            writeMap(JsonObject(map))
        }
    }

    /**
     * Tri-state existence probe — see [KeyVaultBackend.has]'s contract.
     *
     * Unlike [get], this never attempts DPAPI decryption — it only checks whether an encrypted
     * blob is present under [key] in the on-disk map, mirroring the presence-only checks in
     * [MacOsKeychainBackend.has] and [LinuxSecretToolBackend.has].
     *
     * Distinguishes "storage file legitimately does not exist yet" (definitely absent — `false`)
     * from "storage file exists but could not be read/parsed" (backend error — `null`, e.g. an
     * AV/OneDrive file lock or corruption after a crash). The default [KeyVaultBackend.has]
     * cannot make this distinction because [readMap] collapses both cases to an empty map.
     */
    override fun has(key: String): Boolean? {
        if (!Files.exists(storagePath)) return false
        val map = readMapOrNull() ?: return null
        return map.containsKey(key)
    }

    /** Thin interface so tests can inject a mock instead of requiring a real Windows host. */
    internal interface CryptInterop {
        fun protect(data: ByteArray): ByteArray

        fun unprotect(data: ByteArray): ByteArray
    }

    private fun loadCrypt32(): CryptInterop =
        try {
            val utilClass = Class.forName("com.sun.jna.platform.win32.Crypt32Util")
            object : CryptInterop {
                override fun protect(data: ByteArray): ByteArray =
                    utilClass
                        .getMethod("cryptProtectData", ByteArray::class.java)
                        .invoke(null, data) as ByteArray

                override fun unprotect(data: ByteArray): ByteArray =
                    utilClass
                        .getMethod("cryptUnprotectData", ByteArray::class.java)
                        .invoke(null, data) as ByteArray
            }
        } catch (e: Exception) {
            throw KumlAiException.VaultUnavailable(
                message = "Windows DPAPI (Crypt32Util) not available: ${e.message}",
                cause = e,
            )
        }

    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    private fun readMap(): JsonObject = readMapOrNull() ?: JsonObject(emptyMap())

    /**
     * Like [readMap], but preserves the distinction between "no file yet" (empty map) and
     * "file exists but failed to read/parse" (`null`) — needed by [has] so a transient read
     * failure isn't reported as "definitely no secret configured". [get]/[put]/[delete] use
     * [readMap] and intentionally keep the older fail-to-empty-map behavior; only the tri-state
     * [has] probe needs to tell the two apart.
     */
    private fun readMapOrNull(): JsonObject? {
        if (!Files.exists(storagePath)) return JsonObject(emptyMap())
        return try {
            val text = Files.readString(storagePath, StandardCharsets.UTF_8)
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMap(data: JsonObject) {
        try {
            Files.createDirectories(storagePath.parent)
            val tmp = Files.createTempFile(storagePath.parent, "secrets-dpapi", ".tmp")
            Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), data), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, storagePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            throw KumlAiException.VaultUnavailable(
                message = "Cannot write DPAPI storage to $storagePath: ${e.message}",
                cause = e,
            )
        }
    }
}
