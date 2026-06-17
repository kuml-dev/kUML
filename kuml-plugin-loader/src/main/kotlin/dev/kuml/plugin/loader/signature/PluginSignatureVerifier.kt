package dev.kuml.plugin.loader.signature

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies Ed25519 signatures on plugin JARs.
 *
 * ## Signature scheme
 * `signature = base64(Ed25519.sign(sha256(jarBytes)))`
 *
 * The public key is stored in the registry index (`signaturePublicKey` field)
 * as a Base64-encoded DER/X.509 public key (`SubjectPublicKeyInfo` format).
 *
 * ## Key generation (for plugin authors)
 * ```bash
 * openssl genpkey -algorithm ed25519 -out private.pem
 * openssl pkey -in private.pem -pubout -outform DER | base64 > public.b64
 * ```
 *
 * ## Signing a JAR
 * ```bash
 * sha256=$(sha256sum plugin.jar | awk '{print $1}')
 * openssl pkeyutl -sign -inkey private.pem -in <(echo -n "$sha256" | xxd -r -p) \
 *   | base64 > plugin.jar.sig
 * ```
 */
public object PluginSignatureVerifier {
    /**
     * Verify [jarBytes] against [signatureBase64] using [publicKeyBase64].
     *
     * @param jarBytes         Raw bytes of the plugin JAR
     * @param signatureBase64  Base64-encoded Ed25519 signature (from manifest or `.sig` file)
     * @param publicKeyBase64  Base64-encoded DER/X.509 Ed25519 public key (from registry index)
     * @return [SignatureVerificationResult.Valid], [SignatureVerificationResult.Invalid],
     *         or [SignatureVerificationResult.Unsigned] if either parameter is null/blank
     */
    public fun verify(
        jarBytes: ByteArray,
        signatureBase64: String?,
        publicKeyBase64: String?,
    ): SignatureVerificationResult {
        if (signatureBase64.isNullOrBlank()) return SignatureVerificationResult.Unsigned
        if (publicKeyBase64.isNullOrBlank()) return SignatureVerificationResult.Unsigned

        return try {
            val sigBytes = Base64.getDecoder().decode(signatureBase64.trim())
            val pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64.trim())

            val pubKeySpec = X509EncodedKeySpec(pubKeyBytes)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(pubKeySpec)

            // Digest: sha256 of jar bytes → sign the digest
            val digest = MessageDigest.getInstance("SHA-256").digest(jarBytes)

            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(digest)
            val valid = sig.verify(sigBytes)

            if (valid) {
                val fingerprint =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(pubKeyBytes)
                        .take(8)
                        .joinToString(":") { "%02X".format(it) }
                SignatureVerificationResult.Valid(fingerprint)
            } else {
                SignatureVerificationResult.Invalid("Signature does not match JAR content")
            }
        } catch (e: IllegalArgumentException) {
            SignatureVerificationResult.Invalid("Invalid Base64 encoding: ${e.message}")
        } catch (e: java.security.InvalidKeyException) {
            SignatureVerificationResult.Invalid("Invalid public key: ${e.message}")
        } catch (e: java.security.SignatureException) {
            SignatureVerificationResult.Invalid("Signature verification error: ${e.message}")
        } catch (e: Exception) {
            SignatureVerificationResult.Invalid("Unexpected error: ${e.message}")
        }
    }

    /**
     * Read a `.sig` file alongside a JAR (convention: `plugin.jar` → `plugin.jar.sig`).
     * Returns null if the file does not exist or cannot be read.
     */
    public fun readSigFile(jarFile: java.io.File): String? {
        val sigFile = java.io.File(jarFile.parent, "${jarFile.name}.sig")
        return if (sigFile.exists()) {
            runCatching { sigFile.readText().trim() }.getOrNull()
        } else {
            null
        }
    }

    /**
     * Compute the hex fingerprint (first 8 bytes SHA-256, colon-separated) of a Base64 public key.
     * Returns null if the key cannot be decoded.
     */
    public fun publicKeyFingerprint(publicKeyBase64: String): String? =
        runCatching {
            val bytes = Base64.getDecoder().decode(publicKeyBase64.trim())
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .take(8)
                .joinToString(":") { "%02X".format(it) }
        }.getOrNull()
}
