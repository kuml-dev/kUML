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

    /**
     * Tri-state existence probe: `true` = a secret is definitely stored under [key], `false` =
     * definitely absent, `null` = the backend itself failed to answer (a denied OS keystore
     * consent prompt, a locked keychain/keyring, the backing daemon/CLI being unreachable, …) —
     * distinct from a definite "no secret configured".
     *
     * Callers MUST NOT collapse `null` into `false` — see
     * [dev.kuml.desktop.ai.settings.sanitizeSettings]'s KDoc for the destructive-settings-write
     * bug ("fail-destructive on a transient vault error") that this tri-state exists to prevent.
     *
     * The default implementation collapses to [get] — this is only correct for a backend whose
     * [get] *throws* on a genuine backend error rather than swallowing it, so that `get(key) !=
     * null` can never be true-by-accident for a failure. It is NOT safe for a backend whose
     * on-disk read helper catches and discards I/O/parse exceptions internally (a locked file, a
     * corrupted map after a crash, …) — such a backend cannot tell "no secret configured" from
     * "storage unreadable right now" via [get] alone, and MUST override [has] with its own logic
     * that surfaces the read failure as `null` instead of `false`. All four backends shipped in
     * this module override [has] for exactly this reason: [dev.kuml.ai.vault.MacOsKeychainBackend.has]
     * and [dev.kuml.ai.vault.LinuxSecretToolBackend.has] use exit-code-based tri-states because the
     * underlying CLI's exit code conflates "not found" and "backend error";
     * [dev.kuml.ai.vault.WindowsDpapiBackend.has] and [dev.kuml.ai.vault.PlainJsonFallbackBackend.has]
     * distinguish "storage file does not exist" (`false`) from "storage file exists but could not
     * be read/parsed" (`null`) directly, rather than relying on this default.
     */
    public fun has(key: String): Boolean? = get(key) != null

    public companion object {
        /** Canonical key namespace — provider-scoped key. */
        public fun keyFor(provider: LLMProvider): String = "kuml.ai.${provider::class.simpleName?.lowercase()}.apiKey"
    }
}
