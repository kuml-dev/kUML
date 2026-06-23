package dev.kuml.plugin.loader.registry

import kotlinx.serialization.Serializable

/** Lifecycle status of a [PluginSigningKey] in the registry. */
public enum class KeyStatus {
    /** Key is trusted and may be used to verify signatures. */
    ACTIVE,

    /** Key has been explicitly revoked and must not verify signatures. */
    REVOKED,

    /** Key has been explicitly marked as expired in the registry. */
    EXPIRED,
}

/**
 * A single Ed25519 public key entry in a [PluginRegistryEntry]'s `signingKeys` list.
 *
 * V3.1.14 — supports key rotation: multiple keys may exist simultaneously.
 * Verification succeeds if **any** usable key validates the signature.
 *
 * ## Date window
 * A key is considered usable when its [status] is [KeyStatus.ACTIVE] **and** the current
 * date falls within `[validFrom, validUntil]` (both inclusive). A key whose [validUntil]
 * is in the past is treated as implicitly expired even if [status] still says [ACTIVE].
 *
 * ## Recommended rotation procedure
 * 1. Add a new key with `status = ACTIVE` and `validFrom = today`.
 * 2. Keep the old key `ACTIVE` for a 90-day transition period.
 * 3. After 90 days set the old key's `status` to `REVOKED`.
 *
 * @property publicKey  Base64 DER / X.509 `SubjectPublicKeyInfo` — same format used by
 *                      `PluginSignatureVerifier.verify`.
 * @property keyId      Human-readable identifier, e.g. `"2026-primary"`.
 * @property validFrom  ISO-8601 date (inclusive lower bound).
 * @property validUntil ISO-8601 date (inclusive upper bound), or `null` for no expiry.
 * @property status     Registry-declared lifecycle status (default [KeyStatus.ACTIVE]).
 */
@Serializable
public data class PluginSigningKey(
    val publicKey: String,
    val keyId: String,
    val validFrom: String,
    val validUntil: String? = null,
    val status: KeyStatus = KeyStatus.ACTIVE,
) {
    /**
     * Returns `true` if this key may currently be used to verify a signature.
     *
     * A key is usable when:
     * - [status] == [KeyStatus.ACTIVE], **and**
     * - [today] is on or after [validFrom], **and**
     * - [today] is on or before [validUntil] (if set).
     *
     * An `ACTIVE` key whose [validUntil] is in the past is **not** usable
     * (implicitly expired). The [KeyStatus.EXPIRED] enum value is for explicit registry
     * marking; [isUsable] covers both the explicit and the date-implicit case.
     *
     * @param today injectable for deterministic testing; defaults to `LocalDate.now()`.
     */
    public fun isUsable(today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
        if (status != KeyStatus.ACTIVE) return false
        val from = runCatching { java.time.LocalDate.parse(validFrom) }.getOrNull() ?: return false
        if (today.isBefore(from)) return false
        val until = validUntil?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        if (until != null && today.isAfter(until)) return false
        return true
    }
}
