package dev.kuml.runtime.chain.move.aptos

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.time.Instant

/** Test-Account-Adresse (0x + 64 hex chars). */
private const val TEST_ADDRESS = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
private const val TEST_RESOURCE_TYPE = "0x1::kuml::Registry"
private const val TEST_HANDLE_STRUCT = "0x1::kuml::Registry"
private const val TEST_FIELD = "model_events"

/** Erzeugt eine resource-Antwort. */
private fun resourceResponse(
    modelHash: String = "0x01020304",
    modelUri: String = "ipfs://QmTest",
    schemaVersion: String = "2",
): String =
    """{
    "type": "$TEST_RESOURCE_TYPE",
    "data": {
        "model_hash": "$modelHash",
        "model_uri": "$modelUri",
        "schema_version": "$schemaVersion"
    }
}"""

/** Erzeugt ein einzelnes Aptos-Event-JSON. */
private fun aptosEvent(
    version: String = "5",
    eventType: String = "0x1::kuml::ModelUpdated",
    dataJson: String = """{"hash":"0xab","uri":"ipfs://x"}""",
): String =
    """{
    "version": "$version",
    "guid": {"creation_number": "2", "account_address": "$TEST_ADDRESS"},
    "sequence_number": "0",
    "type": "$eventType",
    "data": $dataJson
}"""

/** Erzeugt ein JsonArray von Events als String. */
private fun eventsArray(vararg events: String): String = "[${events.joinToString(",")}]"

private val LEDGER_INFO =
    """{
    "chain_id": 1,
    "ledger_version": "99",
    "block_height": "10",
    "ledger_timestamp": "1700000000000000"
}"""

private val BLOCK_BY_HEIGHT =
    """{
    "block_height": "10",
    "block_hash": "0xblockHash",
    "block_timestamp": "1700000000000000",
    "first_version": "90",
    "last_version": "99"
}"""

class AptosChainAdapterTest :
    StringSpec({

        "connect reads modelHash modelUri schemaVersion from resource" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                server.start()
                try {
                    val adapter =
                        AptosChainAdapter(
                            urlValidator = AptosUrlValidator.NoOp,
                            resourceTypeTag = TEST_RESOURCE_TYPE,
                            eventHandleStruct = TEST_HANDLE_STRUCT,
                            eventFieldName = TEST_FIELD,
                            clientFactory = { url -> AptosRestClient(url) },
                        )
                    val identity = adapter.connect(server.baseUrl(), TEST_ADDRESS)
                    // modelHash "0x01020304" → [1,2,3,4]
                    identity.modelHash.toList() shouldBe listOf<Byte>(1, 2, 3, 4)
                    identity.modelUri shouldBe "ipfs://QmTest"
                    identity.schemaVersion shouldBe 2
                    // composite address
                    identity.address shouldBe "$TEST_ADDRESS::$TEST_HANDLE_STRUCT/$TEST_FIELD"
                } finally {
                    server.stop()
                }
            }
        }

        "connect throws InvalidAddressException for invalid address" {
            runTest {
                val adapter = AptosChainAdapter(urlValidator = AptosUrlValidator.NoOp)
                shouldThrow<AptosChainAdapterException.InvalidAddressException> {
                    adapter.connect("http://localhost:9999", "zzz")
                }
            }
        }

        "connect throws InvalidUrlException for private IP SSRF" {
            runTest {
                val adapter = AptosChainAdapter(urlValidator = AptosUrlValidator.Default)
                shouldThrow<AptosChainAdapterException.InvalidUrlException> {
                    adapter.connect("http://10.0.0.1/", TEST_ADDRESS)
                }
            }
        }

        "subscribe emits events from getEvents" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                server.onPath(Regex("/v1/accounts/.*/events/.*")) {
                    200 to eventsArray(aptosEvent(version = "5"))
                }
                server.start()
                try {
                    val adapter =
                        AptosChainAdapter(
                            urlValidator = AptosUrlValidator.NoOp,
                            pollIntervalMillis = 100L,
                            resourceTypeTag = TEST_RESOURCE_TYPE,
                            eventHandleStruct = TEST_HANDLE_STRUCT,
                            eventFieldName = TEST_FIELD,
                            clientFactory = { url -> AptosRestClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_ADDRESS)
                    val events = adapter.subscribe().take(1).toList()
                    events.size shouldBe 1
                    events[0].eventType shouldBe "0x1::kuml::ModelUpdated"
                    events[0].blockNumber shouldBe 5L
                    events[0].txHash shouldBe "5"
                    // payloadAbi should be the data JSON as UTF-8 bytes
                    val payloadStr = String(events[0].payloadAbi, Charsets.UTF_8)
                    Json.parseToJsonElement(payloadStr) // should parse without exception
                } finally {
                    server.stop()
                }
            }
        }

        "subscribe is a cold flow — each collection starts fresh at seq 0" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                var callCount = 0
                server.onPath(Regex("/v1/accounts/.*/events/.*")) {
                    callCount++
                    200 to eventsArray(aptosEvent(version = callCount.toString()))
                }
                server.start()
                try {
                    val adapter =
                        AptosChainAdapter(
                            urlValidator = AptosUrlValidator.NoOp,
                            pollIntervalMillis = 100L,
                            resourceTypeTag = TEST_RESOURCE_TYPE,
                            eventHandleStruct = TEST_HANDLE_STRUCT,
                            eventFieldName = TEST_FIELD,
                            clientFactory = { url -> AptosRestClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_ADDRESS)
                    val events1 = adapter.subscribe().take(1).toList()
                    val events2 = adapter.subscribe().take(1).toList()
                    events1[0].txHash shouldBe "1"
                    events2[0].txHash shouldBe "2"
                } finally {
                    server.stop()
                }
            }
        }

        "replay returns only events with version >= fromBlock" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                server.onPath(Regex("/v1/accounts/.*/events/.*")) {
                    200 to
                        eventsArray(
                            aptosEvent(version = "3"),
                            aptosEvent(version = "5"),
                            aptosEvent(version = "7"),
                        )
                }
                server.start()
                try {
                    val adapter =
                        AptosChainAdapter(
                            urlValidator = AptosUrlValidator.NoOp,
                            pageLimit = 100,
                            resourceTypeTag = TEST_RESOURCE_TYPE,
                            eventHandleStruct = TEST_HANDLE_STRUCT,
                            eventFieldName = TEST_FIELD,
                            clientFactory = { url -> AptosRestClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_ADDRESS)
                    val events = adapter.replay(fromBlock = 5L).toList()
                    events.size shouldBe 2
                    events[0].blockNumber shouldBe 5L
                    events[1].blockNumber shouldBe 7L
                } finally {
                    server.stop()
                }
            }
        }

        "replay terminates on empty events array" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                var callCount = 0
                server.onPath(Regex("/v1/accounts/.*/events/.*")) {
                    callCount++
                    if (callCount == 1) 200 to eventsArray(aptosEvent()) else 200 to "[]"
                }
                server.start()
                try {
                    val adapter =
                        AptosChainAdapter(
                            urlValidator = AptosUrlValidator.NoOp,
                            pageLimit = 1, // trigger second call immediately
                            resourceTypeTag = TEST_RESOURCE_TYPE,
                            eventHandleStruct = TEST_HANDLE_STRUCT,
                            eventFieldName = TEST_FIELD,
                            clientFactory = { url -> AptosRestClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_ADDRESS)
                    val events = adapter.replay(fromBlock = 0L).toList()
                    events.size shouldBe 1
                } finally {
                    server.stop()
                }
            }
        }

        "blockClock currentBlock reflects ledger_version" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                server.onPath(Regex("^/v1/$")) { 200 to LEDGER_INFO }
                server.onPath(Regex("/v1/blocks/by_height/.*")) { 200 to BLOCK_BY_HEIGHT }
                server.start()
                try {
                    val adapter =
                        AptosChainAdapter(
                            urlValidator = AptosUrlValidator.NoOp,
                            resourceTypeTag = TEST_RESOURCE_TYPE,
                            eventHandleStruct = TEST_HANDLE_STRUCT,
                            eventFieldName = TEST_FIELD,
                            clientFactory = { url -> AptosRestClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_ADDRESS)
                    val clock = adapter.blockClock() as AptosBlockClock
                    clock.refresh()
                    clock.currentBlock() shouldBe 99L
                } finally {
                    server.stop()
                }
            }
        }

        "blockClock currentTime derived from block_timestamp microseconds" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                server.onPath(Regex("^/v1/$")) { 200 to LEDGER_INFO }
                server.onPath(Regex("/v1/blocks/by_height/.*")) { 200 to BLOCK_BY_HEIGHT }
                server.start()
                try {
                    val adapter =
                        AptosChainAdapter(
                            urlValidator = AptosUrlValidator.NoOp,
                            resourceTypeTag = TEST_RESOURCE_TYPE,
                            eventHandleStruct = TEST_HANDLE_STRUCT,
                            eventFieldName = TEST_FIELD,
                            clientFactory = { url -> AptosRestClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_ADDRESS)
                    val clock = adapter.blockClock() as AptosBlockClock
                    clock.refresh()
                    // 1700000000000000 µs = 1700000000 seconds exactly
                    clock.currentTime() shouldBe Instant.ofEpochSecond(1700000000L, 0L)
                } finally {
                    server.stop()
                }
            }
        }

        "AptosEventDecoder payloadAbi is data JSON as UTF-8 bytes" {
            val decoder = AptosEventDecoder()
            val json =
                Json
                    .parseToJsonElement(
                        """{
            "version": "42",
            "type": "0x1::kuml::ModelUpdated",
            "data": {"hash": "0xab", "uri": "ipfs://x"}
        }""",
                    ).let { it as kotlinx.serialization.json.JsonObject }
            val event = decoder.decode(json)
            event.eventType shouldBe "0x1::kuml::ModelUpdated"
            event.blockNumber shouldBe 42L
            event.txHash shouldBe "42"
            val payloadStr = String(event.payloadAbi, Charsets.UTF_8)
            // payloadAbi round-trips as valid JSON
            Json.parseToJsonElement(payloadStr) // must not throw
            payloadStr.contains("0xab") shouldBe true
            payloadStr.contains("ipfs://x") shouldBe true
        }

        "getEvents returns ApiError on HTTP 404" {
            runTest {
                val server = MockRestServer()
                server.onPath(Regex("/v1/accounts/.*/resource/.*")) { 200 to resourceResponse() }
                server.onPath(Regex("/v1/accounts/.*/events/.*")) {
                    404 to """{"message":"resource not found","error_code":"resource_not_found"}"""
                }
                server.start()
                try {
                    val client = AptosRestClient(server.baseUrl())
                    val ex =
                        shouldThrow<AptosChainAdapterException.ApiError> {
                            client.getEvents(TEST_ADDRESS, TEST_HANDLE_STRUCT, TEST_FIELD, 0L, 10)
                        }
                    ex.httpStatus shouldBe 404
                } finally {
                    server.stop()
                }
            }
        }

        "AptosEventDecoder decodeAll processes JsonArray correctly" {
            val decoder = AptosEventDecoder()
            val arr =
                Json.parseToJsonElement(
                    """[
            {"version":"1","type":"0x1::kuml::E","data":{"a":1}},
            {"version":"2","type":"0x1::kuml::E","data":{"b":2}}
        ]""",
                ) as JsonArray
            val events = decoder.decodeAll(arr)
            events.size shouldBe 2
            events[0].blockNumber shouldBe 1L
            events[1].blockNumber shouldBe 2L
        }
    })
