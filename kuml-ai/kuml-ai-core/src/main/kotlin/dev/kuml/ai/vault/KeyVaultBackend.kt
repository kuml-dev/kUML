package dev.kuml.ai.vault

import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.KumlAiException

/**
 * Lower-level interface implemented per-OS.
 * All methods are synchronous and may shell out to OS keystore tooling.
 */
public interface KeyVaultBackend {
    /** Display name of the backend (used in error messages and UI). */
    public val displayName: String

    /** True if this backend is functional on the running OS/environment. */
    public fun isAvailable(): Boolean

    /**
     * Store or overwrite a secret.
     * Throws [KumlAiException.VaultUnavailable] on hard failure.
     */
    public fun put(
        key: String,
        secret: String,
    )

    /** Read a secret, or null if absent. */
    public fun get(key: String): String?

    /** Remove a secret; no-op if absent. */
    public fun delete(key: String)

    public companion object {
        /** Canonical key namespace — provider-scoped key. */
        public fun keyFor(provider: LLMProvider): String = "kuml.ai.${provider::class.simpleName?.lowercase()}.apiKey"
    }
}
