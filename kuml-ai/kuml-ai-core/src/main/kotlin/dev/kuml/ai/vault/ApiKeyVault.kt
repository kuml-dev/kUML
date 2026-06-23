package dev.kuml.ai.vault

import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.settings.XdgPaths
import org.slf4j.LoggerFactory

/**
 * Facade over the platform-detected backend.
 * Construct via [ApiKeyVault.detect] for production use.
 *
 * V3.1.20: supports optional AES-256-GCM master-password encryption via
 * [enableMasterPassword] / [lock]. The raw backend is wrapped in a
 * [MasterPasswordVaultBackend] decorator; the swap is atomic and thread-safe.
 */
public class ApiKeyVault internal constructor(
    backend: KeyVaultBackend,
) {
    /** Active backend — may be swapped on [enableMasterPassword]. */
    @Volatile
    private var _backend: KeyVaultBackend = backend

    /** Visible for tests / diagnostics. */
    public val backend: KeyVaultBackend get() = _backend

    /** Read an API key for a provider. */
    public fun get(provider: LLMProvider): String? = _backend.get(KeyVaultBackend.keyFor(provider))

    /** Store an API key. */
    public fun put(
        provider: LLMProvider,
        key: String,
    ): Unit = _backend.put(KeyVaultBackend.keyFor(provider), key)

    /** Delete an API key. */
    public fun delete(provider: LLMProvider): Unit = _backend.delete(KeyVaultBackend.keyFor(provider))

    /** True if running on a fallback (plain-text) backend — UI should warn the user. */
    public val isFallback: Boolean
        get() {
            val b = _backend
            return b is PlainJsonFallbackBackend ||
                (b is MasterPasswordVaultBackend && b.inner is PlainJsonFallbackBackend)
        }

    /** True when master-password encryption is active on this vault instance. */
    public val isMasterPasswordEnabled: Boolean get() = _backend is MasterPasswordVaultBackend

    /**
     * Wrap the current backend in a [MasterPasswordVaultBackend] using AES-256-GCM encryption.
     *
     * The raw [masterPassword] CharArray is zero-filled immediately after key derivation —
     * the caller MUST NOT use it after this call. If master-password mode is already active,
     * this replaces the existing encryption context (re-key).
     *
     * After this call all [put]/[get] operations encrypt/decrypt transparently.
     * Call [lock] to zero-fill the derived key in memory.
     */
    public fun enableMasterPassword(masterPassword: CharArray) {
        val innerBackend =
            when (val b = _backend) {
                is MasterPasswordVaultBackend -> b.inner // unwrap first if re-keying
                else -> b
            }
        _backend = MasterPasswordVaultBackend.create(masterPassword, innerBackend)
    }

    /**
     * Zero-fill the in-memory encryption key and revert to the unencrypted inner backend.
     *
     * After this call [isMasterPasswordEnabled] is false and subsequent [get]/[put] calls
     * access the inner backend directly (still encrypted on disk, but key is gone from RAM).
     * No-op if master-password mode is not active.
     */
    public fun lock() {
        val b = _backend
        if (b is MasterPasswordVaultBackend) {
            b.lock()
            _backend = b.inner
        }
    }

    public companion object {
        private val log = LoggerFactory.getLogger(ApiKeyVault::class.java)

        /**
         * OS-aware backend selection:
         *  macOS Keychain → Linux libsecret → Windows DPAPI → plain JSON fallback.
         *
         * Override for tests: set system property `kuml.ai.vault.backend` to one of:
         *  "plain", "macos", "linux", "windows".
         */
        public fun detect(): ApiKeyVault {
            // Test override — highest priority
            System.getProperty("kuml.ai.vault.backend")?.let { force ->
                return ApiKeyVault(forceBackend(force))
            }

            // OS-specific candidate
            val candidate: KeyVaultBackend? =
                when (OsDetection.current()) {
                    OsDetection.Os.MAC -> MacOsKeychainBackend()
                    OsDetection.Os.LINUX -> LinuxSecretToolBackend()
                    OsDetection.Os.WINDOWS -> WindowsDpapiBackend()
                    OsDetection.Os.OTHER -> null
                }

            // Check availability (tool installed, working, etc.)
            if (candidate != null && candidate.isAvailable()) {
                return ApiKeyVault(candidate)
            }

            // Suppress warning if user has explicitly opted in
            val suppressWarning = System.getenv("KUML_AI_PLAIN_OK") == "1"
            if (!suppressWarning) {
                log.warn(
                    "No OS keystore available — storing API keys in PLAIN JSON at {}. " +
                        "Set KUML_AI_PLAIN_OK=1 to suppress this warning.",
                    XdgPaths.plainSecretsPath(),
                )
            }
            return ApiKeyVault(PlainJsonFallbackBackend())
        }

        private fun forceBackend(name: String): KeyVaultBackend =
            when (name.lowercase()) {
                "plain" -> PlainJsonFallbackBackend()
                "macos" -> MacOsKeychainBackend()
                "linux" -> LinuxSecretToolBackend()
                "windows" -> WindowsDpapiBackend()
                else -> PlainJsonFallbackBackend()
            }
    }
}
