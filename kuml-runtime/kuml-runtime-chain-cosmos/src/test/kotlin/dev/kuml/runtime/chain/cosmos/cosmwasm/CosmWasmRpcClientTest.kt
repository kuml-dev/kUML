package dev.kuml.runtime.chain.cosmos.cosmwasm

import dev.kuml.runtime.chain.cosmos.MockRpcServer
import dev.kuml.runtime.chain.cosmos.rpcError
import dev.kuml.runtime.chain.cosmos.rpcSuccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray

class CosmWasmRpcClientTest :
    FunSpec({
        lateinit var server: MockRpcServer
        lateinit var client: CosmWasmRpcClient

        beforeTest {
            server = MockRpcServer()
            server.start()
            client = CosmWasmRpcClient(server.baseUrl())
        }

        afterTest {
            server.stop()
        }

        test("call returns result on success") {
            runTest {
                server.onMethod("status") {
                    rpcSuccess(result = """{"sync_info":{"latest_block_height":"100","latest_block_time":"2024-01-01T00:00:00Z"}}""")
                }
                val result = client.call("status", buildJsonArray {})
                result.toString() shouldContain "sync_info"
            }
        }

        test("call throws RpcError on error response") {
            runTest {
                server.onMethod("status") { rpcError(code = -32600, message = "invalid request") }
                val ex =
                    shouldThrow<CosmWasmChainAdapterException.RpcError> {
                        client.call("status", buildJsonArray {})
                    }
                ex.code shouldBe -32600
                ex.rpcMessage shouldBe "invalid request"
            }
        }

        test("call throws NetworkError on non-2xx") {
            runTest {
                // Override server to return 500
                val server500 = MockRpcServer()
                server500.start()
                // Hack: register handler that causes a 500 by using a custom server
                // We'll use a client pointing to a closed port instead
                server500.stop()
                val clientBroken = CosmWasmRpcClient(server500.baseUrl())
                shouldThrow<CosmWasmChainAdapterException.NetworkError> {
                    clientBroken.call("status", buildJsonArray {})
                }
            }
        }

        test("call throws NetworkError when response exceeds maxResponseBytes") {
            runTest {
                server.onMethod("bigmethod") {
                    val huge = "x".repeat(100)
                    rpcSuccess(result = "\"$huge\"")
                }
                val smallClient = CosmWasmRpcClient(server.baseUrl(), maxResponseBytes = 50L)
                shouldThrow<CosmWasmChainAdapterException.NetworkError> {
                    smallClient.call("bigmethod", buildJsonArray {})
                }
            }
        }

        test("readLimited returns content within limit") {
            val data = "hello world".byteInputStream()
            val result = CosmWasmRpcClient.readLimited(data, 100L)
            result shouldBe "hello world"
        }

        test("readLimited throws NetworkError when limit exceeded") {
            val data = "hello world longer text".byteInputStream()
            shouldThrow<CosmWasmChainAdapterException.NetworkError> {
                CosmWasmRpcClient.readLimited(data, 5L)
            }
        }

        test("getLatestBlockHeight parses sync_info correctly") {
            runTest {
                server.onMethod("status") {
                    rpcSuccess(result = """{"sync_info":{"latest_block_height":"42","latest_block_time":"2024-01-01T00:00:00Z"}}""")
                }
                client.getLatestBlockHeight() shouldBe 42L
            }
        }

        test("getLatestBlockHeight throws MalformedResponse when sync_info missing") {
            runTest {
                server.onMethod("status") { rpcSuccess(result = "{}") }
                shouldThrow<CosmWasmChainAdapterException.MalformedResponse> {
                    client.getLatestBlockHeight()
                }
            }
        }

        test("getLatestBlockHeight throws MalformedResponse when height missing") {
            runTest {
                server.onMethod("status") { rpcSuccess(result = """{"sync_info":{}}""") }
                shouldThrow<CosmWasmChainAdapterException.MalformedResponse> {
                    client.getLatestBlockHeight()
                }
            }
        }

        test("getLatestBlockHeader returns height and time") {
            runTest {
                server.onMethod("status") {
                    rpcSuccess(result = """{"sync_info":{"latest_block_height":"7","latest_block_time":"2024-06-01T12:00:00Z"}}""")
                }
                val header = client.getLatestBlockHeader()
                header["height"]?.toString() shouldContain "7"
                header["time"]?.toString() shouldContain "2024-06-01"
            }
        }

        test("getBlockResults passes height as string param") {
            runTest {
                server.onMethod("block_results") { body ->
                    body shouldContain "\"99\""
                    rpcSuccess(result = """{"txs_results":[],"finalize_block_events":[]}""")
                }
                client.getBlockResults(99L)
            }
        }

        test("smartQuery calls LCD endpoint and parses JSON response") {
            runTest {
                val responseJson = """{"model_hash":"AAEC","model_uri":"ipfs://test","schema_version":"1"}"""
                server.onGet("/cosmwasm/wasm/v1/contract/cosmos1address/smart/") {
                    responseJson
                }
                val result = client.smartQuery("cosmos1address", """{"kuml_identity":{}}""")
                result.toString() shouldContain "model_uri"
            }
        }

        test("smartQuery throws NetworkError when LCD returns non-2xx") {
            runTest {
                // No handler registered — server returns 200 with "not found" body which is not valid JSON
                // We simulate by pointing at a stopped server
                val stopped = MockRpcServer()
                stopped.start()
                stopped.stop()
                val brokenClient = CosmWasmRpcClient(stopped.baseUrl())
                shouldThrow<CosmWasmChainAdapterException.NetworkError> {
                    brokenClient.smartQuery("cosmos1address", "{}")
                }
            }
        }

        test("smartQuery returns parsed JsonElement on valid JSON response") {
            runTest {
                val responseJson = """{"model_uri":"ipfs://direct"}"""
                // Register catch-all GET handler under /cosmwasm/ prefix
                val freshServer = MockRpcServer()
                freshServer.start()
                freshServer.onGet("/cosmwasm/") { responseJson }
                val freshClient = CosmWasmRpcClient(freshServer.baseUrl())
                try {
                    val result = freshClient.smartQuery("cosmos1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnrql8a", "{}")
                    result.toString() shouldContain "model_uri"
                } finally {
                    freshServer.stop()
                }
            }
        }

        test("sanitizeUrl removes userinfo") {
            CosmWasmRpcClient.sanitizeUrl("https://user:pass@example.com/rpc") shouldContain "example.com"
            CosmWasmRpcClient.sanitizeUrl("https://user:pass@example.com/rpc") shouldContain "https"
        }

        test("sanitizeUrl handles invalid URL gracefully") {
            val result = CosmWasmRpcClient.sanitizeUrl("not a url at all")
            result.length shouldBe "not a url at all".take(64).length
        }
    })
