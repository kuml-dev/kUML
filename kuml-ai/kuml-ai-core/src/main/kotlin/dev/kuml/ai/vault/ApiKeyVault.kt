package dev.kuml.ai.vault

import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.settings.XdgPaths
import org.slf4j.LoggerFactory

/**
 * Facade over the platform-detected backend.
 * Construct via [ApiKeyVault.detect] for production use.
 */
public class ApiKeyVault internal constructor(
    public val backend: KeyVaultBackend,
) {
    /** Read an API key for a provider. */
    public fun get(provider: LLMProvider): String? = backend.get(KeyVaultBackend.keyFor(provider))

    /** Store an API key. */
    public fun put(
        provider: LLMProvider,
        key: String,
    ): Unit = backend.put(KeyVaultBackend.keyFor(provider), key)

    /** Delete an API key. */
    public fun delete(provider: LLMProvider): Unit = backend.delete(KeyVaultBackend.keyFor(provider))

    /** True if running on a fallback (plain-text) backend — UI should warn the user. */
    public val isFallback: Boolean get() = backend is PlainJsonFallbackBackend

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
