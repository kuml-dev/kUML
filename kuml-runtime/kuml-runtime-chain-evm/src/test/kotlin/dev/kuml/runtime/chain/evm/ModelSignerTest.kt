package dev.kuml.runtime.chain.evm

import dev.kuml.runtime.chain.ModelHasher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

/**
 * Tests für [ModelSigner]: sign/recover/verify mit Hardhat-Standardaccounts.
 *
 * Deterministisch: RFC 6979 garantiert identische Signatur für gleichen digest + privKey.
 * Die internen `sign(source, key, timestamp)` Overloads erlauben gepinnten Timestamp.
 *
 * Hardhat Account #0:
 *   privKey = 0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
 *   addr    = 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
 *
 * Hardhat Account #1:
 *   privKey = 0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
 *   addr    = 0x70997970C51812dc3A010C7d01b50e0d17dc79C8
 */
private const val PRIV_KEY_0 = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
private const val ADDR_0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
private const val PRIV_KEY_1 = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
private const val ADDR_1 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

private const val MODEL_SOURCE = """
diagram("TestDiagram") {
    class_("Alice")
    class_("Bob")
}
"""

private val EIP55_ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")

class ModelSignerTest :
    StringSpec({

        val signer = ModelSigner()
        val verifier = Eip712Verifier()
        val fixedTimestamp = 1_700_000_000L

        // ── sign ─────────────────────────────────────────────────────────────

        "sign: signature is exactly 65 bytes" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            sig.signature.size shouldBe 65
        }

        "sign: modelHash equals hashCanonical(canonicalize(source))" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            val expected = ModelHasher.hashCanonical(ModelHasher.canonicalize(MODEL_SOURCE))
            sig.modelHash.contentEquals(expected) shouldBe true
        }

        "sign: signer is EIP-55 checksummed Ethereum address matching Account#0" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            sig.signer shouldMatch EIP55_ADDRESS_REGEX
            sig.signer shouldBe ADDR_0
        }

        "sign: signer for Account#1 key matches Account#1 address" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_1, fixedTimestamp)
            sig.signer shouldBe ADDR_1
        }

        "sign: two different keys produce different signer addresses" {
            val sig0 = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            val sig1 = signer.sign(MODEL_SOURCE, PRIV_KEY_1, fixedTimestamp)
            sig0.signer shouldNotBe sig1.signer
        }

        "sign: RFC 6979 determinism — same key+source+timestamp → identical signature" {
            val sig1 = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            val sig2 = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            sig1.signature.contentEquals(sig2.signature) shouldBe true
        }

        "sign: v byte is 27 or 28 (EIP recovery id convention)" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            val v = sig.signature[64].toInt() and 0xFF
            (v == 27 || v == 28) shouldBe true
        }

        "sign: rejects invalid private key (all zeros)" {
            var threw = false
            try {
                signer.sign(MODEL_SOURCE, "0x" + "00".repeat(32), fixedTimestamp)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            threw shouldBe true
        }

        "sign: rejects malformed hex key" {
            var threw = false
            try {
                signer.sign(MODEL_SOURCE, "not-a-hex-key", fixedTimestamp)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            threw shouldBe true
        }

        // ── recover ──────────────────────────────────────────────────────────

        "recover: round-trip — recover(source, sign(source, key)) == signer" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            val recovered = signer.recover(MODEL_SOURCE, sig)
            recovered shouldBe ADDR_0
        }

        "recover: Account#1 key recovers Account#1 address" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_1, fixedTimestamp)
            val recovered = signer.recover(MODEL_SOURCE, sig)
            recovered shouldBe ADDR_1
        }

        "recover: different model source than signed → throws IllegalArgumentException" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            var threw = false
            try {
                signer.recover("diagram(\"other\") { }", sig)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            threw shouldBe true
        }

        // ── verifyModelSignature ──────────────────────────────────────────────

        "verifyModelSignature: valid sign → true" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            verifier.verifyModelSignature(MODEL_SOURCE, sig) shouldBe true
        }

        "verifyModelSignature: manipulated modelHash in sig → false" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            val manipulatedHash = sig.modelHash.copyOf()
            manipulatedHash[0] = (manipulatedHash[0].toInt() xor 0xFF).toByte()
            val manipulatedSig =
                dev.kuml.runtime.chain.ModelSignature(
                    signer = sig.signer,
                    signature = sig.signature,
                    modelHash = manipulatedHash,
                    timestamp = sig.timestamp,
                )
            verifier.verifyModelSignature(MODEL_SOURCE, manipulatedSig) shouldBe false
        }

        "verifyModelSignature: different modelSource with same sig → false" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            verifier.verifyModelSignature("diagram(\"tampered\") { }", sig) shouldBe false
        }

        "verifyModelSignature: Account#1 sig not verified as Account#0" {
            val sig1 = signer.sign(MODEL_SOURCE, PRIV_KEY_1, fixedTimestamp)
            // sig1.signer is ADDR_1 — verify check: recovered == sig.signer (both ADDR_1) → true
            // But if we craft a sig with wrong signer field it should fail
            val wrongSignerSig =
                dev.kuml.runtime.chain.ModelSignature(
                    signer = ADDR_0, // wrong signer, signature is from ADDR_1
                    signature = sig1.signature,
                    modelHash = sig1.modelHash,
                    timestamp = sig1.timestamp,
                )
            verifier.verifyModelSignature(MODEL_SOURCE, wrongSignerSig) shouldBe false
        }

        "verifyModelSignature: corrupted signature bytes → false" {
            val sig = signer.sign(MODEL_SOURCE, PRIV_KEY_0, fixedTimestamp)
            val corrupted = sig.signature.copyOf()
            corrupted[0] = (corrupted[0].toInt() xor 0xFF).toByte()
            val corruptedSig =
                dev.kuml.runtime.chain.ModelSignature(
                    signer = sig.signer,
                    signature = corrupted,
                    modelHash = sig.modelHash,
                    timestamp = sig.timestamp,
                )
            verifier.verifyModelSignature(MODEL_SOURCE, corruptedSig) shouldBe false
        }
    })
