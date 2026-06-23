package dev.kuml.plugin.loader.signature

import dev.kuml.plugin.loader.registry.KeyStatus
import dev.kuml.plugin.loader.registry.PluginSigningKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.LocalDate
import java.util.Base64

class PluginSignatureVerifierTest :
    StringSpec({

        // Generate a test Ed25519 key pair (shared across all single-key tests)
        val keyPair = KeyPairGenerator.getInstance("Ed25519").genKeyPair()
        val pubKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        fun signBytes(
            data: ByteArray,
            kp: java.security.KeyPair = keyPair,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(kp.private)
            sig.update(digest)
            return Base64.getEncoder().encodeToString(sig.sign())
        }

        val jarBytes = "fake jar content".toByteArray()

        // ── existing single-key verify() tests (regression guard) ─────────────

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

        // ── V3.1.14: verifyWithKeys() tests ────────────────────────────────────

        val today = LocalDate.of(2026, 6, 23)

        fun activeKey(
            kp: java.security.KeyPair = keyPair,
            keyId: String = "2026-primary",
        ): PluginSigningKey =
            PluginSigningKey(
                publicKey = Base64.getEncoder().encodeToString(kp.public.encoded),
                keyId = keyId,
                validFrom = "2026-01-01",
                validUntil = null,
                status = KeyStatus.ACTIVE,
            )

        "verifyWithKeys: single ACTIVE key, valid signature returns Valid with keyId" {
            val sig = signBytes(jarBytes)
            val keys = listOf(activeKey(keyId = "2026-primary"))
            val result = PluginSignatureVerifier.verifyWithKeys(jarBytes, sig, keys, today)
            val valid = result.shouldBeInstanceOf<SignatureVerificationResult.Valid>()
            valid.keyId shouldBe "2026-primary"
        }

        "verifyWithKeys: two ACTIVE keys, signed by key A returns Valid with A keyId" {
            val kpA = keyPair
            val kpB = KeyPairGenerator.getInstance("Ed25519").genKeyPair()
            val sig = signBytes(jarBytes, kpA)
            val keys = listOf(activeKey(kpA, "key-A"), activeKey(kpB, "key-B"))
            val result = PluginSignatureVerifier.verifyWithKeys(jarBytes, sig, keys, today)
            val valid = result.shouldBeInstanceOf<SignatureVerificationResult.Valid>()
            valid.keyId shouldBe "key-A"
        }

        "verifyWithKeys: two ACTIVE keys, signed by key B returns Valid with B keyId" {
            val kpA = KeyPairGenerator.getInstance("Ed25519").genKeyPair()
            val kpB = KeyPairGenerator.getInstance("Ed25519").genKeyPair()
            val sig = signBytes(jarBytes, kpB)
            val keys = listOf(activeKey(kpA, "key-A"), activeKey(kpB, "key-B"))
            val result = PluginSignatureVerifier.verifyWithKeys(jarBytes, sig, keys, today)
            val valid = result.shouldBeInstanceOf<SignatureVerificationResult.Valid>()
            valid.keyId shouldBe "key-B"
        }

        "verifyWithKeys: only REVOKED key returns Invalid" {
            val sig = signBytes(jarBytes)
            val revokedKey =
                PluginSigningKey(
                    publicKey = pubKeyBase64,
                    keyId = "revoked-key",
                    validFrom = "2025-01-01",
                    status = KeyStatus.REVOKED,
                )
            val result = PluginSignatureVerifier.verifyWithKeys(jarBytes, sig, listOf(revokedKey), today)
            result.shouldBeInstanceOf<SignatureVerificationResult.Invalid>()
        }

        "verifyWithKeys: ACTIVE key with past validUntil (date-expired) returns Invalid" {
            val sig = signBytes(jarBytes)
            val expiredKey =
                PluginSigningKey(
                    publicKey = pubKeyBase64,
                    keyId = "expired-key",
                    validFrom = "2024-01-01",
                    validUntil = "2025-12-31", // expired relative to today = 2026-06-23
                    status = KeyStatus.ACTIVE,
                )
            val result = PluginSignatureVerifier.verifyWithKeys(jarBytes, sig, listOf(expiredKey), today)
            result.shouldBeInstanceOf<SignatureVerificationResult.Invalid>()
        }

        "verifyWithKeys: empty key list returns Invalid with descriptive message" {
            val sig = signBytes(jarBytes)
            val result = PluginSignatureVerifier.verifyWithKeys(jarBytes, sig, emptyList(), today)
            val invalid = result.shouldBeInstanceOf<SignatureVerificationResult.Invalid>()
            invalid.reason shouldBe "No active signing key available"
        }

        "verifyWithKeys: all keys non-usable (EXPIRED status) returns Invalid" {
            val sig = signBytes(jarBytes)
            val expiredStatusKey =
                PluginSigningKey(
                    publicKey = pubKeyBase64,
                    keyId = "old-key",
                    validFrom = "2023-01-01",
                    status = KeyStatus.EXPIRED,
                )
            val result = PluginSignatureVerifier.verifyWithKeys(jarBytes, sig, listOf(expiredStatusKey), today)
            result.shouldBeInstanceOf<SignatureVerificationResult.Invalid>()
        }

        "verifyWithKeys: null/blank signature returns Unsigned regardless of keys" {
            val keys = listOf(activeKey())
            PluginSignatureVerifier.verifyWithKeys(jarBytes, null, keys, today) shouldBe
                SignatureVerificationResult.Unsigned
            PluginSignatureVerifier.verifyWithKeys(jarBytes, "  ", keys, today) shouldBe
                SignatureVerificationResult.Unsigned
        }
    })
