package dev.kuml.runtime.chain.cosmos.substrate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SubstrateEventDecoderTest :
    StringSpec({
        val decoder = SubstrateEventDecoder()

        "decodeContractEmitted returns empty for empty hex" {
            decoder.decodeContractEmitted("", "5GrwvaEF", 1L, "0xhash").shouldBeEmpty()
        }

        "decodeContractEmitted returns empty for 0x empty" {
            decoder.decodeContractEmitted("0x", "5GrwvaEF", 1L, "0xhash").shouldBeEmpty()
        }

        "hexToBytes converts valid hex" {
            val bytes = decoder.hexToBytes("0xdeadbeef")
            bytes shouldBe byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        }

        "hexToBytes converts without 0x prefix" {
            val bytes = decoder.hexToBytes("deadbeef")
            bytes shouldBe byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        }

        "hexToBytes returns empty for empty string" {
            decoder.hexToBytes("0x").size shouldBe 0
            decoder.hexToBytes("").size shouldBe 0
        }

        "hexToBytes throws MalformedResponse for odd length" {
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                decoder.hexToBytes("0xabc")
            }
        }

        "hexToBytes throws MalformedResponse for invalid hex chars" {
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                decoder.hexToBytes("0xGGGG")
            }
        }

        "decodeIdentity decodes valid SCALE result" {
            // Build a minimal SCALE-encoded identity result:
            // - discriminant 0 (Ok)
            // - model_hash: compact(4) + [1,2,3,4]
            // - model_uri: compact(7) + "ipfs://x"
            // - schema_version: u32 LE = 1
            val modelHash = byteArrayOf(1, 2, 3, 4)
            val modelUri = "ipfs://x"
            val uriBytes = modelUri.toByteArray(Charsets.UTF_8)

            val scaleBytes =
                byteArrayOf(0) + // discriminant Ok
                    byteArrayOf((modelHash.size * 4).toByte()) + // compact(4) = 0x10
                    modelHash +
                    byteArrayOf((uriBytes.size * 4).toByte()) + // compact(9) = 0x24... wait
                    uriBytes +
                    byteArrayOf(1, 0, 0, 0) // u32 LE = 1

            // Fix compact encoding: compact(n) for small n is n << 2
            // compact(4) = 4 << 2 = 16 = 0x10
            // compact(9) = 9 << 2 = 36 = 0x24
            val fixedUriCompact = (uriBytes.size shl 2).toByte()
            val fixedHashCompact = (modelHash.size shl 2).toByte()
            val finalBytes =
                byteArrayOf(0, fixedHashCompact) + modelHash +
                    byteArrayOf(fixedUriCompact) + uriBytes +
                    byteArrayOf(1, 0, 0, 0)

            val hex = "0x" + finalBytes.joinToString("") { "%02x".format(it) }
            val result = decoder.decodeIdentity(hex)
            result.modelHash shouldBe modelHash
            result.modelUri shouldBe modelUri
            result.schemaVersion shouldBe 1
        }

        "decodeIdentity throws MalformedResponse for empty result" {
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                decoder.decodeIdentity("")
            }
        }

        "decodeIdentity throws MalformedResponse for Err discriminant" {
            // discriminant = 1 (Err)
            val hex = "0x01"
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                decoder.decodeIdentity(hex)
            }
        }

        "ScaleReader readCompact handles single-byte mode" {
            val reader = ScaleReader(byteArrayOf(0x10)) // 0x10 = 16 = 4 << 2 → 4
            reader.readCompact() shouldBe 4
        }

        "ScaleReader readCompact handles two-byte mode" {
            // mode = 0x01 (bits 0-1 = 01), value = 0xFC41 >> 2 (upper 14 bits)
            // Example: 0x_FC_01 → mode=1, value = (0x01 >> 2) | (0xFC << 6) = 0 | 16320 = 16320... complex
            // Simple: 0x_01 means 2-byte mode, 0x_05 = 5*4+1=21... just test readCompact returns expected
            val reader = ScaleReader(byteArrayOf(0x01, 0x01)) // 01 = mode 01 (2-byte), value = (01>>2) | (01 << 6) = 0|64 = 64
            reader.readCompact() shouldBe 64
        }

        "decodeContractEmitted returns empty for malformed SCALE" {
            // Random bytes that don't form valid SCALE
            val garbage = "0x" + "ff".repeat(4)
            val result = decoder.decodeContractEmitted(garbage, "5GrwvaEF", 1L, "0xhash")
            result.shouldBeEmpty()
        }

        "ScaleReader readCompact throws MalformedResponse for big-integer mode overflow" {
            // Mode 3 (big-integer): first byte & 0x03 == 3.
            // first byte 0x03 → mode=3, extraBytes = (0x03 >> 2) + 4 = 4
            // Encode 0xFFFFFFFF (4,294,967,295 > Int.MAX_VALUE) in 4 bytes LE:
            val bigMode =
                byteArrayOf(
                    0x03, // mode=3, extraBytes=4
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0xFF.toByte(),
                )
            val reader = ScaleReader(bigMode)
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                reader.readCompact()
            }
        }

        "ScaleReader position() returns current read offset" {
            val reader = ScaleReader(byteArrayOf(0x10, 0x20, 0x30))
            reader.position() shouldBe 0
            reader.readByte()
            reader.position() shouldBe 1
        }
    })
