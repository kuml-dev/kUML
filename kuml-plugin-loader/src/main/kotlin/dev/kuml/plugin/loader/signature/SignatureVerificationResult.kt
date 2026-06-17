package dev.kuml.plugin.loader.signature

/**
 * Result of verifying a plugin JAR's Ed25519 signature.
 *
 * V3.0.30: EdDSA via Java 21 built-in `java.security` (no external dep).
 */
public sealed interface SignatureVerificationResult {
    /** JAR was signed and the signature is valid against the declared public key. */
    public data class Valid(
        /** Hex fingerprint of the verifying public key. */
        val publicKeyFingerprint: String,
    ) : SignatureVerificationResult

    /** Signature is present but cryptographically invalid. */
    public data class Invalid(
        val reason: String,
    ) : SignatureVerificationResult

    /** No signature was provided (manifest `signature` field is null). */
    public object Unsigned : SignatureVerificationResult
}
