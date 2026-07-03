package dev.kuml.runtime.chain.cosmos.cosmwasm

import dev.kuml.runtime.chain.cosmos.Base64Codec
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class CosmWasmEventDecoderTest :
    StringSpec({
        val decoder = CosmWasmEventDecoder()
        val contractAddr = "cosmos1qyqszqgpqyqszqgpqyqszqgpqyqszqgp8k3yd3"

        fun plainAttr(
            key: String,
            value: String,
        ) = buildJsonObject {
            put("key", key)
            put("value", value)
        }

        fun base64Attr(
            key: String,
            value: String,
        ) = buildJsonObject {
            put("key", Base64Codec.encode(key.toByteArray()))
            put("value", Base64Codec.encode(value.toByteArray()))
        }

        fun blockResults(vararg events: kotlinx.serialization.json.JsonObject) =
            buildJsonObject {
                putJsonArray("finalize_block_events") {
                    events.forEach { add(it) }
                }
                putJsonArray("txs_results") {}
            }

        fun wasmEvent(attrs: List<kotlinx.serialization.json.JsonObject>): kotlinx.serialization.json.JsonObject =
            buildJsonObject {
                put("type", "wasm")
                putJsonArray("attributes") {
                    attrs.forEach { add(it) }
                }
            }

        "decodes wasm event with plain attributes" {
            val evt =
                wasmEvent(
                    listOf(
                        plainAttr("_contract_address", contractAddr),
                        plainAttr("action", "transfer"),
                        plainAttr("amount", "100"),
                    ),
                )
            val results = blockResults(evt)
            val events = decoder.decodeWasmEvents(results, contractAddr, 10L)
            events shouldHaveSize 1
            events[0].eventType shouldBe "transfer"
            events[0].blockNumber shouldBe 10L
        }

        "decodes wasm event with base64 attributes (Tendermint < 0.38)" {
            val evt =
                wasmEvent(
                    listOf(
                        base64Attr("_contract_address", contractAddr),
                        base64Attr("action", "execute"),
                    ),
                )
            val results = blockResults(evt)
            val events = decoder.decodeWasmEvents(results, contractAddr, 5L)
            events shouldHaveSize 1
            events[0].eventType shouldBe "execute"
        }

        "filters out events from other contracts" {
            val otherAddr = "cosmos1zg69v7yszg69v7yszg69v7yszg69v7ysx5xvh3"
            val evt =
                wasmEvent(
                    listOf(
                        plainAttr("_contract_address", otherAddr),
                        plainAttr("action", "transfer"),
                    ),
                )
            val results = blockResults(evt)
            val events = decoder.decodeWasmEvents(results, contractAddr, 1L)
            events.shouldBeEmpty()
        }

        "ignores non-wasm event types" {
            val evt =
                buildJsonObject {
                    put("type", "transfer")
                    putJsonArray("attributes") {
                        add(plainAttr("sender", "cosmos1abc"))
                    }
                }
            val results = blockResults(evt)
            val events = decoder.decodeWasmEvents(results, contractAddr, 1L)
            events.shouldBeEmpty()
        }

        "returns empty list for empty block results" {
            val results =
                buildJsonObject {
                    putJsonArray("finalize_block_events") {}
                    putJsonArray("txs_results") {}
                }
            val events = decoder.decodeWasmEvents(results, contractAddr, 1L)
            events.shouldBeEmpty()
        }

        "decodes events from txs_results" {
            val txEvent =
                wasmEvent(
                    listOf(
                        plainAttr("_contract_address", contractAddr),
                        plainAttr("action", "mint"),
                    ),
                )
            val results =
                buildJsonObject {
                    putJsonArray("finalize_block_events") {}
                    putJsonArray("txs_results") {
                        add(
                            buildJsonObject {
                                put("txhash", "TXHASH123")
                                putJsonArray("events") {
                                    add(txEvent)
                                }
                            },
                        )
                    }
                }
            val events = decoder.decodeWasmEvents(results, contractAddr, 3L)
            events shouldHaveSize 1
            events[0].eventType shouldBe "mint"
            events[0].txHash shouldBe "TXHASH123"
        }

        "payload contains non-contract-address non-action attributes" {
            val evt =
                wasmEvent(
                    listOf(
                        plainAttr("_contract_address", contractAddr),
                        plainAttr("action", "transfer"),
                        plainAttr("amount", "500"),
                        plainAttr("recipient", "cosmos1abc"),
                    ),
                )
            val results = blockResults(evt)
            val events = decoder.decodeWasmEvents(results, contractAddr, 1L)
            events shouldHaveSize 1
            val payload = String(events[0].payloadAbi, Charsets.UTF_8)
            payload shouldBe """{"amount":"500","recipient":"cosmos1abc"}"""
        }

        "decodeAttributes detects base64 via first key" {
            val attrs =
                buildJsonArray {
                    add(base64Attr("_contract_address", contractAddr))
                    add(base64Attr("action", "burn"))
                }
            val result = decoder.decodeAttributes(attrs)
            result["_contract_address"] shouldBe contractAddr
            result["action"] shouldBe "burn"
        }

        "decodeAttributes plain text when not base64" {
            val attrs =
                buildJsonArray {
                    add(plainAttr("_contract_address", contractAddr))
                    add(plainAttr("action", "stake"))
                }
            val result = decoder.decodeAttributes(attrs)
            result["_contract_address"] shouldBe contractAddr
            result["action"] shouldBe "stake"
        }
    })
