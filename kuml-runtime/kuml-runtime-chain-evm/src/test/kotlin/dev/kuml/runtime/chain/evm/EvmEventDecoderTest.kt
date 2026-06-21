package dev.kuml.runtime.chain.evm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EvmEventDecoderTest :
    StringSpec({

        val decoder = EvmEventDecoder()

        "decode maps topic0 to eventType and data to payloadAbi" {
            val log =
                buildJsonObject {
                    put(
                        "topics",
                        buildJsonArray { add(JsonPrimitive("0xddf252ad")) },
                    )
                    put("data", "0xdeadbeef")
                    put("blockNumber", "0x2a")
                    put("transactionHash", "0xtx1")
                }
            val event = decoder.decode(log)
            event.eventType shouldBe "0xddf252ad"
            event.payloadAbi.size shouldBe 4
            event.payloadAbi[0] shouldBe 0xde.toByte()
            event.payloadAbi[1] shouldBe 0xad.toByte()
            event.payloadAbi[2] shouldBe 0xbe.toByte()
            event.payloadAbi[3] shouldBe 0xef.toByte()
        }

        "decode reads blockNumber hex and transactionHash" {
            val log =
                buildJsonObject {
                    put("topics", buildJsonArray { add(JsonPrimitive("0xabc")) })
                    put("data", "0x01")
                    put("blockNumber", "0x2a")
                    put("transactionHash", "0xtxhash99")
                }
            val event = decoder.decode(log)
            event.blockNumber shouldBe 42L
            event.txHash shouldBe "0xtxhash99"
        }

        "decode with empty 0x data yields empty payloadAbi" {
            val log =
                buildJsonObject {
                    put("topics", buildJsonArray { add(JsonPrimitive("0x1234")) })
                    put("data", "0x")
                    put("blockNumber", "0x1")
                    put("transactionHash", "0xtxempty")
                }
            val event = decoder.decode(log)
            event.payloadAbi.isEmpty() shouldBe true
        }

        "decode throws MalformedResponse when topics array is empty" {
            val log =
                buildJsonObject {
                    put("topics", buildJsonArray {})
                    put("data", "0x")
                    put("blockNumber", "0x1")
                    put("transactionHash", "0xtx")
                }
            shouldThrow<EvmChainAdapterException.MalformedResponse> {
                decoder.decode(log)
            }
        }
    })
