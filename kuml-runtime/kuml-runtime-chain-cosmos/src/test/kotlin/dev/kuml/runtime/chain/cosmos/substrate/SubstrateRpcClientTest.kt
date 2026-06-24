package dev.kuml.runtime.chain.cosmos.substrate

import dev.kuml.runtime.chain.cosmos.MockRpcServer
import dev.kuml.runtime.chain.cosmos.rpcError
import dev.kuml.runtime.chain.cosmos.rpcSuccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray

class SubstrateRpcClientTest :
    FunSpec({
        lateinit var server: MockRpcServer
        lateinit var client: SubstrateRpcClient

        beforeTest {
            server = MockRpcServer()
            server.start()
            client = SubstrateRpcClient(server.baseUrl())
        }

        afterTest { server.stop() }

        test("call returns result on success") {
            runTest {
                server.onMethod("chain_getFinalizedHead") { rpcSuccess(result = "\"0xabc123\"") }
                val result = client.call("chain_getFinalizedHead", buildJsonArray {})
                result.toString() shouldContain "abc123"
            }
        }

        test("call throws RpcError on error response") {
            runTest {
                server.onMethod("chain_getFinalizedHead") { rpcError(code = -32000, message = "chain error") }
                val ex =
                    shouldThrow<SubstrateChainAdapterException.RpcError> {
                        client.call("chain_getFinalizedHead", buildJsonArray {})
                    }
                ex.code shouldBe -32000
                ex.rpcMessage shouldBe "chain error"
            }
        }

        test("call throws NetworkError when server unavailable") {
            runTest {
                val deadServer = MockRpcServer()
                deadServer.start()
                deadServer.stop()
                val deadClient = SubstrateRpcClient(deadServer.baseUrl())
                shouldThrow<SubstrateChainAdapterException.NetworkError> {
                    deadClient.call("any_method", buildJsonArray {})
                }
            }
        }

        test("getFinalizedHeight parses hex number from header") {
            runTest {
                server.onMethod("chain_getFinalizedHead") { rpcSuccess(result = "\"0xhashvalue\"") }
                server.onMethod("chain_getHeader") { rpcSuccess(result = """{"number":"0x2A"}""") }
                client.getFinalizedHeight() shouldBe 42L
            }
        }

        test("getFinalizedHeight throws MalformedResponse when number missing") {
            runTest {
                server.onMethod("chain_getFinalizedHead") { rpcSuccess(result = "\"0xhash\"") }
                server.onMethod("chain_getHeader") { rpcSuccess(result = "{}") }
                shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                    client.getFinalizedHeight()
                }
            }
        }

        test("getBlockHash returns hex string") {
            runTest {
                server.onMethod("chain_getBlockHash") { body ->
                    body shouldContain "100"
                    rpcSuccess(result = "\"0xblockhash\"")
                }
                client.getBlockHash(100L) shouldBe "0xblockhash"
            }
        }

        test("getBlockHash throws MalformedResponse on null result") {
            runTest {
                server.onMethod("chain_getBlockHash") { rpcSuccess(result = "null") }
                shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                    client.getBlockHash(999L)
                }
            }
        }

        test("getSystemEvents returns empty string on null") {
            runTest {
                server.onMethod("state_getStorage") { rpcSuccess(result = "null") }
                client.getSystemEvents("0xhash") shouldBe ""
            }
        }

        test("getSystemEvents returns hex string") {
            runTest {
                server.onMethod("state_getStorage") { rpcSuccess(result = "\"0xdeadbeef\"") }
                client.getSystemEvents("0xhash") shouldBe "0xdeadbeef"
            }
        }

        test("getTimestampNowHex returns empty string on null") {
            runTest {
                server.onMethod("state_getStorage") { rpcSuccess(result = "null") }
                client.getTimestampNowHex() shouldBe ""
            }
        }

        test("contractsCall uses state_call ContractsApi_call and extracts data from Ok result") {
            runTest {
                // Alice's full SS58 address for a valid base58Decode
                val aliceAddr = "5GrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN"
                server.onMethod("state_call") {
                    rpcSuccess(result = """{"result":{"Ok":{"data":"0xdeadbeef"}}}""")
                }
                client.contractsCall(aliceAddr, "0x9bae9d5e") shouldBe "0xdeadbeef"
            }
        }

        test("contractsCall accepts plain hex string from standard Substrate node") {
            runTest {
                // Standard Substrate nodes return state_call result as a plain hex JsonPrimitive,
                // not a nested object. This is the real-world form — the test above covers
                // RPC-proxy wrappers.
                val aliceAddr = "5GrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN"
                server.onMethod("state_call") {
                    rpcSuccess(result = "\"0xcafebabe\"")
                }
                client.contractsCall(aliceAddr, "0x9bae9d5e") shouldBe "0xcafebabe"
            }
        }

        test("contractsCall throws MalformedResponse when data missing") {
            runTest {
                val aliceAddr = "5GrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN"
                server.onMethod("state_call") { rpcSuccess(result = "{}") }
                shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                    client.contractsCall(aliceAddr, "0x9bae9d5e")
                }
            }
        }

        test("toHttpUrl converts wss to https") {
            SubstrateRpcClient.toHttpUrl("wss://example.com/rpc") shouldBe "https://example.com/rpc"
        }

        test("toHttpUrl converts ws to http") {
            SubstrateRpcClient.toHttpUrl("ws://example.com/rpc") shouldBe "http://example.com/rpc"
        }

        test("toHttpUrl leaves https unchanged") {
            SubstrateRpcClient.toHttpUrl("https://example.com/rpc") shouldBe "https://example.com/rpc"
        }

        test("parseHexQuantity converts 0x hex to Long") {
            SubstrateRpcClient.parseHexQuantity("0x1A") shouldBe 26L
            SubstrateRpcClient.parseHexQuantity("0x0") shouldBe 0L
            SubstrateRpcClient.parseHexQuantity("0x") shouldBe 0L
        }

        test("parseHexQuantity throws MalformedResponse for invalid hex") {
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                SubstrateRpcClient.parseHexQuantity("0xGGGG")
            }
        }

        test("sanitizeUrl removes userinfo from wss URL") {
            val result = SubstrateRpcClient.sanitizeUrl("wss://user:pass@example.com/ws")
            result shouldContain "example.com"
        }

        test("readLimited returns content within limit") {
            val data = "substrate data".byteInputStream()
            SubstrateRpcClient.readLimited(data, 100L) shouldBe "substrate data"
        }

        test("readLimited throws NetworkError when limit exceeded") {
            val data = "too much data here".byteInputStream()
            shouldThrow<SubstrateChainAdapterException.NetworkError> {
                SubstrateRpcClient.readLimited(data, 5L)
            }
        }
    })
