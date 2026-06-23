package dev.kuml.plugin.loader.signature

/**
 * Result of verifying a plugin JAR's Ed25519 signature.
 *
 * V3.0.30: EdDSA via Java 21 built-in `java.security` (no external dep).
 * V3.1.14: [Valid] gains an optional [Valid.keyId] field for key-rotation support.
 */
public sealed interface SignatureVerificationResult {
    /**
     * JAR was signed and the signature is valid against the declared public key.
     *
     * @property publicKeyFingerprint Hex fingerprint of the verifying public key
     *                                (first 8 bytes SHA-256, colon-separated).
     * @property keyId                Registry key identifier (e.g. `"2026-primary"`)
     *                                when verified via [PluginSignatureVerifier.verifyWithKeys];
     *                                `null` when verified via the single-key [PluginSignatureVerifier.verify].
     */
    public data class Valid(
        /** Hex fingerprint of the verifying public key. */
        val publicKeyFingerprint: String,
        /** Registry key ID, or `null` for single-key verification paths. */
        val keyId: String? = null,
    ) : SignatureVerificationResult

    /** Signature is present but cryptographically invalid. */
    public data class Invalid(
        val reason: String,
    ) : SignatureVerificationResult

    /** No signature was provided (manifest `signature` field is null). */
    public object Unsigned : SignatureVerificationResult
}
