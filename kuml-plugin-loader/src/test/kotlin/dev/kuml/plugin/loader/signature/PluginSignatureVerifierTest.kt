package dev.kuml.plugin.loader.signature

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class PluginSignatureVerifierTest :
    StringSpec({

        // Generate a test Ed25519 key pair
        val keyPair = KeyPairGenerator.getInstance("Ed25519").genKeyPair()
        val pubKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        fun signBytes(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(keyPair.private)
            sig.update(digest)
            return Base64.getEncoder().encodeToString(sig.sign())
        }

        val jarBytes = "fake jar content".toByteArray()

        "verify: valid signature returns Valid with fingerprint" {
            val sig = signBytes(jarBytes)
            val result = PluginSignatureVerifier.verify(jarBytes, sig, pubKeyBase64)
            val valid = result.shouldBeInstanceOf<SignatureVerificationResult.Valid>()
            valid.publicKeyFingerprint.contains(":") shouldBe true
        }

        "verify: tampered bytes returns Invalid" {
            val sig = signBytes(jarBytes)
            val tamperedBytes = "different content".toByteArray()
            val result = PluginSignatureVerifier.verify(tamperedBytes, sig, pubKeyBase64)
            result.shouldBeInstanceOf<SignatureVerificationResult.Invalid>()
        }

        "verify: null signature returns Unsigned" {
            val result = PluginSignatureVerifier.verify(jarBytes, null, pubKeyBase64)
            result shouldBe SignatureVerificationResult.Unsigned
        }

        "verify: blank signature returns Unsigned" {
            val result = PluginSignatureVerifier.verify(jarBytes, "  ", pubKeyBase64)
            result shouldBe SignatureVerificationResult.Unsigned
        }

        "verify: null public key returns Unsigned" {
            val sig = signBytes(jarBytes)
            val result = PluginSignatureVerifier.verify(jarBytes, sig, null)
            result shouldBe SignatureVerificationResult.Unsigned
        }

        "verify: invalid base64 returns Invalid" {
            val result = PluginSignatureVerifier.verify(jarBytes, "not-base64!!", pubKeyBase64)
            result.shouldBeInstanceOf<SignatureVerificationResult.Invalid>()
        }

        "verify: wrong key returns Invalid" {
            val otherKeyPair = KeyPairGenerator.getInstance("Ed25519").genKeyPair()
            val wrongPubKey = Base64.getEncoder().encodeToString(otherKeyPair.public.encoded)
            val sig = signBytes(jarBytes)
            val result = PluginSignatureVerifier.verify(jarBytes, sig, wrongPubKey)
            result.shouldBeInstanceOf<SignatureVerificationResult.Invalid>()
        }

        "publicKeyFingerprint: returns colon-separated hex" {
            val fp = PluginSignatureVerifier.publicKeyFingerprint(pubKeyBase64)
            fp?.contains(":") shouldBe true
            fp?.length shouldBe 23 // 8 bytes × 2 hex + 7 colons
        }

        "publicKeyFingerprint: invalid base64 returns null" {
            val fp = PluginSignatureVerifier.publicKeyFingerprint("!!not-valid!!")
            fp shouldBe null
        }
    })
