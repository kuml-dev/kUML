package dev.kuml.runtime.chain.evm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

// ── ABI encoding helpers for tests ───────────────────────────────────────────

/** Encodes a fixed 32-byte slot (bytes32 / uint256) — returns hex without 0x. */
private fun abiBytes32(hex: String): String = hex.padStart(64, '0')

/** ABI-encodes a uint256 value. */
private fun abiUint(value: Int): String = value.toString(16).padStart(64, '0')

/** ABI-encodes a dynamic string (offset=0x20, length, data padded to 32-byte boundary). */
private fun abiString(s: String): String {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val offset = "0000000000000000000000000000000000000000000000000000000000000020"
    val length = bytes.size.toString(16).padStart(64, '0')
    val hexData = bytes.joinToString("") { "%02x".format(it) }
    val padded = hexData.padEnd(((hexData.length + 63) / 64) * 64, '0')
    return offset + length + (if (padded.isEmpty()) "0".repeat(64) else padded)
}

/** Returns an ABI-encoded eth_call result prefixed with "0x". */
private fun abiCallResult(hex: String): String = rpcSuccess(result = "\"0x$hex\"")

/** Gültige Test-Contract-Adresse (0x + 40 Hex-Zeichen). */
private const val TEST_CONTRACT = "0x1234567890abcdef1234567890abcdef12345678"

class EvmChainAdapterTest :
    StringSpec({

        "connect rejects blank rpcUrl and blank contractAddress" {
            runTest {
                val adapter = EvmChainAdapter(urlValidator = RpcUrlValidator.NoOp)
                shouldThrow<IllegalArgumentException> {
                    adapter.connect("", "0xabc")
                }
                shouldThrow<IllegalArgumentException> {
                    adapter.connect("http://localhost:8545", "")
                }
                // Ungültige Adressformate (kein 0x+40-Hex) müssen abgelehnt werden
                shouldThrow<IllegalArgumentException> {
                    adapter.connect("http://localhost:8545", "0xabc")
                }
                shouldThrow<IllegalArgumentException> {
                    adapter.connect("http://localhost:8545", "not-an-address")
                }
            }
        }

        "connect reads modelHash modelUri schemaVersion via eth_call" {
            runTest {
                val server = MockRpcServer()

                val modelHashHex = abiBytes32("42")
                val modelUriHex = abiString("ipfs://QmTest")
                val schemaVersionHex = abiUint(3)

                server.onMethod("eth_call") { body ->
                    when {
                        body.contains(EvmChainAdapter.SELECTOR_MODEL_HASH.removePrefix("0x")) ->
                            abiCallResult(modelHashHex)
                        body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                            abiCallResult(modelUriHex)
                        body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                            abiCallResult(schemaVersionHex)
                        else -> rpcError(code = -32000, message = "unknown selector")
                    }
                }
                server.start()
                try {
                    val adapter =
                        EvmChainAdapter(
                            clientFactory = { url -> EvmJsonRpcClient(url) },
                            urlValidator = RpcUrlValidator.NoOp,
                        )
                    val identity = adapter.connect(server.baseUrl(), TEST_CONTRACT)
                    identity.address shouldBe TEST_CONTRACT
                    identity.modelHash[31] shouldBe 0x42.toByte()
                    identity.modelUri shouldBe "ipfs://QmTest"
                    identity.schemaVersion shouldBe 3
                } finally {
                    server.stop()
                }
            }
        }

        "replay paginates getLogs over block range and terminates at head" {
            runTest {
                val server = MockRpcServer()

                server.onMethod("eth_call") { body ->
                    when {
                        body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                            abiCallResult(abiString("ipfs://stub"))
                        body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                            abiCallResult(abiUint(1))
                        else -> abiCallResult(abiBytes32("00"))
                    }
                }

                server.onMethod("eth_blockNumber") { rpcSuccess(result = "\"0x5\"") }

                server.onMethod("eth_getLogs") {
                    rpcSuccess(
                        result =
                            """[
                            {"topics":["0xaaa"],"data":"0x01","blockNumber":"0x1","transactionHash":"0xtx1"},
                            {"topics":["0xbbb"],"data":"0x02","blockNumber":"0x2","transactionHash":"0xtx2"}
                        ]""",
                    )
                }

                server.start()
                try {
                    val adapter =
                        EvmChainAdapter(
                            logPageSize = 10L,
                            clientFactory = { url -> EvmJsonRpcClient(url) },
                            urlValidator = RpcUrlValidator.NoOp,
                        )
                    adapter.connect(server.baseUrl(), TEST_CONTRACT)

                    val events = adapter.replay(fromBlock = 0L).toList()
                    events.size shouldBe 2
                    events[0].eventType shouldBe "0xaaa"
                    events[1].eventType shouldBe "0xbbb"
                } finally {
                    server.stop()
                }
            }
        }

        "subscribe detects block-hash change at lastSeen height as ReorgDetected" {
            runTest {
                val server = MockRpcServer()
                var blockNumberCallCount = 0
                var getBlockByNumberCallCount = 0

                server.onMethod("eth_call") { body ->
                    when {
                        body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                            abiCallResult(abiString("ipfs://stub"))
                        body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                            abiCallResult(abiUint(1))
                        else -> abiCallResult(abiBytes32("00"))
                    }
                }

                server.onMethod("eth_blockNumber") {
                    blockNumberCallCount++
                    when (blockNumberCallCount) {
                        1 -> rpcSuccess(result = "\"0x2\"")
                        else -> rpcSuccess(result = "\"0x3\"")
                    }
                }

                server.onMethod("eth_getBlockByNumber") {
                    getBlockByNumberCallCount++
                    when (getBlockByNumberCallCount) {
                        1 ->
                            rpcSuccess(
                                result = """{"number":"0x2","hash":"0xoriginal","timestamp":"0x1"}""",
                            )
                        else ->
                            rpcSuccess(
                                result = """{"number":"0x2","hash":"0xreorged","timestamp":"0x1"}""",
                            )
                    }
                }

                server.onMethod("eth_getLogs") { rpcSuccess(result = "[]") }

                server.start()
                try {
                    val adapter =
                        EvmChainAdapter(
                            pollIntervalMillis = 1L,
                            clientFactory = { url -> EvmJsonRpcClient(url) },
                            urlValidator = RpcUrlValidator.NoOp,
                        )
                    adapter.connect(server.baseUrl(), TEST_CONTRACT)

                    val ex =
                        shouldThrow<EvmChainAdapterException.ReorgDetected> {
                            adapter.subscribe().toList()
                        }
                    ex.reorgFromBlock shouldBe 2L
                    ex.expectedBlockHash shouldBe "0xoriginal"
                    ex.actualBlockHash shouldBe "0xreorged"
                } finally {
                    server.stop()
                }
            }
        }
    })
