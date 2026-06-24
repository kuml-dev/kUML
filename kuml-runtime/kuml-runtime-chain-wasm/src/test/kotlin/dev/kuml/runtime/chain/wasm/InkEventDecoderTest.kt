package dev.kuml.runtime.chain.wasm

import dev.kuml.runtime.chain.wasm.ink.InkAbiMetadata
import dev.kuml.runtime.chain.wasm.ink.InkEventDecoder
import dev.kuml.runtime.chain.wasm.scale.ScaleCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

private val ABI_V5 =
    """
{
  "version": "5",
  "types": [
    {"id": 1, "type": {"def": {"primitive": "u32"}}},
    {"id": 2, "type": {"def": {"primitive": "bool"}}},
    {"id": 3, "type": {"def": {"primitive": "str"}}},
    {"id": 4, "type": {"def": {"primitive": "u8"}}},
    {"id": 5, "type": {"def": {"array": {"len": 32, "type": 4}}}},
    {"id": 6, "type": {"def": {"composite": {"fields": [{"name": "x", "type": 1}]}}}}
  ],
  "spec": {
    "events": [
      {
        "label": "Transfer",
        "signature_topic": "0xaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd",
        "args": [
          {"label": "from", "type": {"type": 5}, "indexed": true},
          {"label": "to",   "type": {"type": 5}, "indexed": true},
          {"label": "amount", "type": {"type": 1}, "indexed": false},
          {"label": "success", "type": {"type": 2}, "indexed": false},
          {"label": "memo", "type": {"type": 3}, "indexed": false}
        ]
      },
      {
        "label": "Approval",
        "signature_topic": "0x1111111111111111111111111111111111111111111111111111111111111111",
        "args": [
          {"label": "amount", "type": {"type": 1}, "indexed": false}
        ]
      },
      {
        "label": "WithComposite",
        "signature_topic": "0x2222222222222222222222222222222222222222222222222222222222222222",
        "args": [
          {"label": "data", "type": {"type": 6}, "indexed": false}
        ]
      }
    ],
    "messages": []
  }
}
    """.trimIndent()

private val ABI_V4 =
    """
{
  "version": "4",
  "types": [
    {"id": 1, "type": {"def": {"primitive": "u64"}}}
  ],
  "spec": {
    "events": [
      {
        "label": "Deposited",
        "args": [{"label": "amount", "type": {"type": 1}, "indexed": false}]
      }
    ],
    "messages": []
  }
}
    """.trimIndent()

class InkEventDecoderTest :
    FunSpec({

        fun makeDecoder(abiJson: String): InkEventDecoder {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(abiJson))
            return InkEventDecoder(abi)
        }

        // -------------------------------------------------------------------------
        // v5: signature_topic identifies event; non-indexed fields decoded from data
        // -------------------------------------------------------------------------

        test("v5: Transfer — non-indexed fields (u32+bool+str) decoded from data blob") {
            val decoder = makeDecoder(ABI_V5)
            // Build data blob: u32(42) + bool(true) + str("hi")
            val amount = ScaleCodec.encodeU32(42L)
            val success = byteArrayOf(0x01) // bool true
            val memo = ScaleCodec.encodeCompact(2L) + "hi".toByteArray(Charsets.UTF_8) // Vec<u8> = str
            val dataBlob = amount + success + memo

            val fromTopicHex = "0x" + "ab".repeat(32)
            val toTopicHex = "0x" + "cd".repeat(32)

            val result =
                decoder.decode(
                    listOf("0xaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd", fromTopicHex, toTopicHex),
                    dataBlob,
                )
            result.shouldNotBeNull()
            result.name shouldBe "Transfer"
            result.values["amount"] shouldBe 42L
            result.values["success"] shouldBe true
            result.values["memo"] shouldBe "hi"
        }

        test("v5: indexed fields come from topics, not from data blob") {
            val decoder = makeDecoder(ABI_V5)
            val fromTopicHex = "0x" + "ab".repeat(32)
            val toTopicHex = "0x" + "cd".repeat(32)
            // data blob: only non-indexed fields (amount + success + memo)
            val dataBlob = ScaleCodec.encodeU32(99L) + byteArrayOf(0x00) + byteArrayOf(0x00) // empty str
            val result =
                decoder.decode(
                    listOf("0xaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd", fromTopicHex, toTopicHex),
                    dataBlob,
                )
            result.shouldNotBeNull()
            result.values["from"] shouldBe fromTopicHex
            result.values["to"] shouldBe toTopicHex
            // data cursor must not have consumed topic bytes
            result.values["amount"] shouldBe 99L
        }

        test("v5: AccountId32 field (array[u8;32]) → 32-byte ByteArray") {
            // Transfer has indexed AccountId32 fields, but Approval has just a u32.
            // Use a simplified ABI with a non-indexed AccountId32 field for this test.
            val abiWithAccountId =
                """
                {
                  "version":"5",
                  "types":[
                    {"id":1,"type":{"def":{"primitive":"u8"}}},
                    {"id":2,"type":{"def":{"array":{"len":32,"type":1}}}}
                  ],
                  "spec":{
                    "events":[{
                      "label":"Registered",
                      "signature_topic":"0x3333333333333333333333333333333333333333333333333333333333333333",
                      "args":[{"label":"account","type":{"type":2},"indexed":false}]
                    }],
                    "messages":[]
                  }
                }
                """.trimIndent()
            val decoder = makeDecoder(abiWithAccountId)
            val accountBytes = ByteArray(32) { it.toByte() }
            val result =
                decoder.decode(
                    listOf("0x3333333333333333333333333333333333333333333333333333333333333333"),
                    accountBytes,
                )
            result.shouldNotBeNull()
            (result.values["account"] as ByteArray).contentEquals(accountBytes) shouldBe true
        }

        test("v5: unknown signature_topic → null (foreign contract event)") {
            val decoder = makeDecoder(ABI_V5)
            val result =
                decoder.decode(
                    listOf("0x0000000000000000000000000000000000000000000000000000000000000000"),
                    ByteArray(0),
                )
            result.shouldBeNull()
        }

        test("v5: Composite field → returns raw remaining bytes (resilience, no exception)") {
            val decoder = makeDecoder(ABI_V5)
            val rawPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val result =
                decoder.decode(
                    listOf("0x2222222222222222222222222222222222222222222222222222222222222222"),
                    rawPayload,
                )
            result.shouldNotBeNull()
            result.name shouldBe "WithComposite"
            // Composite field must come back as a ByteArray containing the raw remaining bytes
            (result.values["data"] as ByteArray).contentEquals(rawPayload) shouldBe true
        }

        test("v5: i256 primitive field decoded without exception") {
            val abiWithI256 =
                """
                {
                  "version":"5",
                  "types":[{"id":1,"type":{"def":{"primitive":"i256"}}}],
                  "spec":{
                    "events":[{
                      "label":"BigSigned",
                      "signature_topic":"0x4444444444444444444444444444444444444444444444444444444444444444",
                      "args":[{"label":"val","type":{"type":1},"indexed":false}]
                    }],
                    "messages":[]
                  }
                }
                """.trimIndent()
            val decoder = makeDecoder(abiWithI256)
            val data = ByteArray(16) { it.toByte() }
            val result =
                decoder.decode(
                    listOf("0x4444444444444444444444444444444444444444444444444444444444444444"),
                    data,
                )
            result.shouldNotBeNull()
            result.name shouldBe "BigSigned"
            (result.values["val"] as String).startsWith("0x") shouldBe true
        }

        test("v5: Variant field (Option-like) decoded as label string") {
            val abiWithVariant =
                """
                {
                  "version":"5",
                  "types":[
                    {"id":1,"type":{"def":{"variant":{"variants":[
                      {"index":0,"name":"None"},
                      {"index":1,"name":"Some"}
                    ]}}}}
                  ],
                  "spec":{
                    "events":[{
                      "label":"Opted",
                      "signature_topic":"0x5555555555555555555555555555555555555555555555555555555555555555",
                      "args":[{"label":"opt","type":{"type":1},"indexed":false}]
                    }],
                    "messages":[]
                  }
                }
                """.trimIndent()
            val decoder = makeDecoder(abiWithVariant)
            // discriminant 0 = None
            val result =
                decoder.decode(
                    listOf("0x5555555555555555555555555555555555555555555555555555555555555555"),
                    byteArrayOf(0x00),
                )
            result.shouldNotBeNull()
            result.values["opt"] shouldBe "None"
        }

        // -------------------------------------------------------------------------
        // v4 Fallback: first topic byte as event index
        // -------------------------------------------------------------------------

        test("v4 fallback: first topic byte = event index 0 → Deposited") {
            val decoder = makeDecoder(ABI_V4)
            // v4: first topic byte = 0x00 (index 0)
            val topic = "0x" + "00" + "0".repeat(62)
            val dataBlob = ScaleCodec.encodeU64(12345L)
            val result = decoder.decode(listOf(topic), dataBlob)
            result.shouldNotBeNull()
            result.name shouldBe "Deposited"
            result.values["amount"] shouldBe 12345L
        }

        test("v4 fallback: first topic byte = out of range → null") {
            val decoder = makeDecoder(ABI_V4)
            val topic = "0x" + "ff" + "0".repeat(62) // index 255, no such event
            val result = decoder.decode(listOf(topic), ByteArray(0))
            result.shouldBeNull()
        }

        test("empty topics list → null") {
            val decoder = makeDecoder(ABI_V5)
            val result = decoder.decode(emptyList(), ByteArray(0))
            result.shouldBeNull()
        }
    })
