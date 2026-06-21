package dev.kuml.runtime.chain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Unit-Tests für [ModelSignature]: JSON-Roundtrip, equals/hashCode-Korrektheit. */
class ModelSignatureTest :
    StringSpec({

        val sampleSigner = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
        val sampleSignature = ByteArray(65) { (it + 1).toByte() }
        val sampleModelHash = ByteArray(32) { it.toByte() }
        val sampleTimestamp = 1_700_000_000L

        fun makeSignature(
            signer: String = sampleSigner,
            signature: ByteArray = sampleSignature.copyOf(),
            modelHash: ByteArray = sampleModelHash.copyOf(),
            timestamp: Long = sampleTimestamp,
        ) = ModelSignature(signer, signature, modelHash, timestamp)

        "JSON roundtrip produces equal instance" {
            val original = makeSignature()
            val json = original.toJson()
            val restored = ModelSignature.fromJson(json)
            restored shouldBe original
        }

        "equals: two instances with same content are equal (separate ByteArray objects)" {
            val a = makeSignature()
            val b = makeSignature()
            // Verify the ByteArrays are separate objects (not same reference)
            (a.signature === b.signature) shouldBe false
            a shouldBe b
        }

        "equals: different modelHash → not equal" {
            val a = makeSignature()
            val b = makeSignature(modelHash = ByteArray(32) { (it + 1).toByte() })
            (a == b) shouldBe false
        }

        "equals: different signer → not equal" {
            val a = makeSignature()
            val b = makeSignature(signer = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
            (a == b) shouldBe false
        }

        "equals: different signature bytes → not equal" {
            val a = makeSignature()
            val altSig = ByteArray(65) { (it + 42).toByte() }
            val b = makeSignature(signature = altSig)
            (a == b) shouldBe false
        }

        "equals: different timestamp → not equal" {
            val a = makeSignature()
            val b = makeSignature(timestamp = sampleTimestamp + 1)
            (a == b) shouldBe false
        }

        "hashCode consistent: equal instances have same hashCode" {
            val a = makeSignature()
            val b = makeSignature()
            a shouldBe b
            a.hashCode() shouldBe b.hashCode()
        }

        "hashCode map/set tauglichkeit: instances usable as map keys" {
            val a = makeSignature()
            val b = makeSignature()
            val map = hashMapOf(a to "found")
            map[b] shouldBe "found"
        }

        "JSON contains signer field as string" {
            val json = makeSignature().toJson()
            (json.contains(sampleSigner)) shouldBe true
        }

        "JSON contains modelHash as lowercase hex (not base64)" {
            val json = makeSignature().toJson()
            // First byte of sampleModelHash is 0x00 → present as "00" hex
            (json.contains("000102")) shouldBe true
        }

        "toString contains signer and timestamp" {
            val sig = makeSignature()
            val s = sig.toString()
            (s.contains(sampleSigner)) shouldBe true
            (s.contains(sampleTimestamp.toString())) shouldBe true
        }

        "fromJson rejects malformed JSON" {
            var threw = false
            try {
                ModelSignature.fromJson("{ not valid json }")
            } catch (_: Exception) {
                threw = true
            }
            threw shouldBe true
        }

        "instance is not equal to null" {
            val sig = makeSignature()
            (sig.equals(null)) shouldBe false
        }

        "instance is not equal to other type" {
            val sig = makeSignature()
            (sig.equals("string")) shouldBe false
        }

        "instance equals itself (reflexive)" {
            val sig = makeSignature()
            (sig == sig) shouldBe true
        }
    })
