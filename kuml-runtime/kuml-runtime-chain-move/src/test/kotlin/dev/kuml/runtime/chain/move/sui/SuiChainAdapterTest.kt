package dev.kuml.runtime.chain.move.sui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.Base64

/** gültiges 64-Hex-Zeichen Object-ID für Tests */
private const val TEST_OBJECT_ID = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
private const val TEST_PACKAGE_ID = "0xdeadbeef"
private const val TEST_TYPE = "0xdeadbeef::kuml::KumlRegistry"
private const val TEST_EVENT_TYPE = "0xdeadbeef::kuml::KumlModelUpdated"

/** Minimale sui_getObject-Antwort mit Int-Array model_hash. */
private fun getObjectResponse(
    modelHashJson: String = "[1,2,3,4]",
    modelUri: String = "ipfs://QmTest",
    schemaVersion: String = "1",
): String =
    rpcSuccess(
        result = """{
        "data": {
            "objectId": "$TEST_OBJECT_ID",
            "version": "5",
            "content": {
                "dataType": "moveObject",
                "type": "$TEST_TYPE",
                "fields": {
                    "model_hash": $modelHashJson,
                    "model_uri": "$modelUri",
                    "schema_version": "$schemaVersion"
                }
            }
        }
    }""",
    )

/** Erzeugt eine queryEvents-Antwort mit einem Event. */
private fun queryEventsResponse(
    events: String = "[]",
    hasNextPage: Boolean = false,
    nextCursor: String? = null,
): String {
    val cursorJson = if (nextCursor != null) """{"txDigest":"$nextCursor","eventSeq":"0"}""" else "null"
    return rpcSuccess(
        result = """{
        "data": $events,
        "nextCursor": $cursorJson,
        "hasNextPage": $hasNextPage
    }""",
    )
}

/** Erzeugt ein einzelnes Sui-Event-JSON. */
private fun suiEvent(
    eventType: String = TEST_EVENT_TYPE,
    bcsBase64: String = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
    checkpoint: String = "5",
    txDigest: String = "TxAbc123",
): String =
    """{
    "id": {"txDigest": "$txDigest", "eventSeq": "0"},
    "packageId": "$TEST_PACKAGE_ID",
    "transactionModule": "kuml",
    "type": "$eventType",
    "bcs": "$bcsBase64",
    "timestampMs": "1700000000000",
    "checkpoint": "$checkpoint"
}"""

class SuiChainAdapterTest :
    StringSpec({

        "connect reads modelHash as Int-Array from sui_getObject" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse(modelHashJson = "[1,2,3,4]") }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    val identity = adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    identity.address shouldBe TEST_OBJECT_ID
                    identity.modelHash.toList() shouldBe listOf<Byte>(1, 2, 3, 4)
                    identity.modelUri shouldBe "ipfs://QmTest"
                    identity.schemaVersion shouldBe 1
                } finally {
                    server.stop()
                }
            }
        }

        "connect reads modelHash as Base64 string" {
            runTest {
                val server = MockRpcServer()
                val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
                server.onMethod("sui_getObject") { getObjectResponse(modelHashJson = "\"$b64\"") }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    val identity = adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    identity.modelHash.toList() shouldBe listOf<Byte>(1, 2, 3, 4)
                } finally {
                    server.stop()
                }
            }
        }

        "connect reads modelHash as hex string" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse(modelHashJson = "\"0x01020304\"") }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    val identity = adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    identity.modelHash.toList() shouldBe listOf<Byte>(1, 2, 3, 4)
                } finally {
                    server.stop()
                }
            }
        }

        "connect throws InvalidObjectId for invalid address format" {
            runTest {
                val adapter = SuiChainAdapter(urlValidator = SuiRpcUrlValidator.NoOp)
                shouldThrow<SuiChainAdapterException.InvalidObjectId> {
                    adapter.connect("http://localhost:9999", "nothex")
                }
            }
        }

        "connect throws InvalidUrlException for private IP (SSRF protection)" {
            runTest {
                val adapter = SuiChainAdapter(urlValidator = SuiRpcUrlValidator.Default)
                shouldThrow<SuiChainAdapterException.InvalidUrlException> {
                    adapter.connect("http://127.0.0.1:8080/", TEST_OBJECT_ID)
                }
            }
        }

        "connect throws InvalidUrlException for 10.x.x.x private IP" {
            runTest {
                val adapter = SuiChainAdapter(urlValidator = SuiRpcUrlValidator.Default)
                shouldThrow<SuiChainAdapterException.InvalidUrlException> {
                    adapter.connect("http://10.0.0.1/", TEST_OBJECT_ID)
                }
            }
        }

        "subscribe emits events from queryEvents" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse() }
                server.onMethod("suix_queryEvents") {
                    queryEventsResponse(
                        events = "[${suiEvent()}]",
                        hasNextPage = false,
                    )
                }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            pollIntervalMillis = 100L,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    val events = adapter.subscribe().take(1).toList()
                    events.size shouldBe 1
                    events[0].eventType shouldBe TEST_EVENT_TYPE
                    events[0].payloadAbi.toList() shouldBe listOf<Byte>(1, 2, 3)
                    events[0].blockNumber shouldBe 5L
                    events[0].txHash shouldBe "TxAbc123"
                } finally {
                    server.stop()
                }
            }
        }

        "subscribe is a cold flow — each collection starts fresh" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse() }
                var queryCount = 0
                server.onMethod("suix_queryEvents") {
                    queryCount++
                    queryEventsResponse(
                        events = "[${suiEvent(txDigest = "Tx$queryCount")}]",
                        hasNextPage = false,
                    )
                }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            pollIntervalMillis = 100L,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    val events1 = adapter.subscribe().take(1).toList()
                    val events2 = adapter.subscribe().take(1).toList()
                    // Cold flow: both collections started a new subscription
                    events1[0].txHash shouldBe "Tx1"
                    events2[0].txHash shouldBe "Tx2"
                } finally {
                    server.stop()
                }
            }
        }

        "replay returns only events with checkpoint >= fromBlock" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse() }
                server.onMethod("suix_queryEvents") {
                    queryEventsResponse(
                        events = """[
                    ${suiEvent(checkpoint = "3", txDigest = "Tx3")},
                    ${suiEvent(checkpoint = "5", txDigest = "Tx5")},
                    ${suiEvent(checkpoint = "7", txDigest = "Tx7")}
                ]""",
                        hasNextPage = false,
                    )
                }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    val events = adapter.replay(fromBlock = 5L).toList()
                    events.size shouldBe 2
                    events[0].blockNumber shouldBe 5L
                    events[1].blockNumber shouldBe 7L
                } finally {
                    server.stop()
                }
            }
        }

        "replay terminates when hasNextPage is false" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse() }
                server.onMethod("suix_queryEvents") {
                    queryEventsResponse(events = "[${suiEvent()}]", hasNextPage = false)
                }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    val events = adapter.replay(fromBlock = 0L).toList()
                    events.size shouldBe 1
                } finally {
                    server.stop()
                }
            }
        }

        "blockClock currentBlock reflects latest checkpoint" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse() }
                server.onMethod("suix_getLatestCheckpointSequenceNumber") { rpcSuccess(result = "\"12\"") }
                server.onMethod("sui_getCheckpoint") {
                    rpcSuccess(result = """{"sequenceNumber":"12","timestampMs":"1700000000000"}""")
                }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    val clock = adapter.blockClock() as SuiBlockClock
                    clock.refresh()
                    clock.currentBlock() shouldBe 12L
                } finally {
                    server.stop()
                }
            }
        }

        "blockClock currentTime is Instant from timestampMs milliseconds" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("sui_getObject") { getObjectResponse() }
                server.onMethod("suix_getLatestCheckpointSequenceNumber") { rpcSuccess(result = "\"1\"") }
                server.onMethod("sui_getCheckpoint") {
                    rpcSuccess(result = """{"sequenceNumber":"1","timestampMs":"1700000000000"}""")
                }
                server.start()
                try {
                    val adapter =
                        SuiChainAdapter(
                            urlValidator = SuiRpcUrlValidator.NoOp,
                            clientFactory = { url -> SuiRpcClient(url) },
                        )
                    adapter.connect(server.baseUrl(), TEST_OBJECT_ID)
                    val clock = adapter.blockClock() as SuiBlockClock
                    clock.refresh()
                    // 1700000000000 ms = Instant.ofEpochMilli(1700000000000)
                    clock.currentTime() shouldBe Instant.ofEpochMilli(1700000000000L)
                } finally {
                    server.stop()
                }
            }
        }

        "SuiEventDecoder decodes eventType and payloadAbi from bcs field" {
            val decoder = SuiEventDecoder()
            val b64 = Base64.getEncoder().encodeToString(byteArrayOf(0x0A, 0x0B, 0x0C))
            val json =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        """{
            "id": {"txDigest": "DigestXYZ", "eventSeq": "0"},
            "type": "0xpkg::mod::MyEvent",
            "bcs": "$b64",
            "checkpoint": "42"
        }""",
                    ).let { it as kotlinx.serialization.json.JsonObject }
            val event = decoder.decode(json)
            event.eventType shouldBe "0xpkg::mod::MyEvent"
            event.payloadAbi.toList() shouldBe listOf<Byte>(0x0A, 0x0B, 0x0C)
            event.blockNumber shouldBe 42L
            event.txHash shouldBe "DigestXYZ"
        }

        "SuiEventDecoder decode handles empty bcs field gracefully" {
            val decoder = SuiEventDecoder()
            val json =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        """{
            "id": {"txDigest": "Tx0", "eventSeq": "0"},
            "type": "0xpkg::mod::Evt",
            "bcs": "",
            "checkpoint": "1"
        }""",
                    ).let { it as kotlinx.serialization.json.JsonObject }
            val event = decoder.decode(json)
            event.payloadAbi.size shouldBe 0
        }
    })
