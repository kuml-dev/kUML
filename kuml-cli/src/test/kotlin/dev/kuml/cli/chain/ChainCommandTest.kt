package dev.kuml.cli.chain

import com.github.ajalt.clikt.testing.test
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.ModelHasher
import dev.kuml.runtime.chain.evm.EvmChainAdapter
import dev.kuml.runtime.chain.evm.RpcUrlValidator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/** Gültige Test-Contract-Adresse (0x + 40 Hex-Zeichen). */
private const val TEST_CONTRACT = "0x1234567890abcdef1234567890abcdef12345678"

/**
 * Factory für einen test-fähigen EvmChainAdapter:
 * NoOp-SSRF-Validator erlaubt localhost-Verbindungen zum MockRpcServer.
 */
private fun testAdapterFactory(): () -> KumlChainAdapter =
    {
        EvmChainAdapter(
            urlValidator = RpcUrlValidator.NoOp,
            pollIntervalMillis = 10L,
            logPageSize = 2_000L,
        )
    }

/**
 * Registriert die drei eth_call-Handler für connect() am MockRpcServer.
 * [modelHashHex] muss 64 Zeichen Hex ohne "0x" sein (32 Bytes).
 */
private fun registerConnectHandlers(
    server: MockRpcServer,
    modelHashHex: String,
    modelUri: String,
    schemaVersion: Int,
) {
    server.onMethod("eth_call") { body ->
        when {
            body.contains(EvmChainAdapter.SELECTOR_MODEL_HASH.removePrefix("0x")) ->
                abiCallResult(abiBytes32(modelHashHex))
            body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                abiCallResult(abiString(modelUri))
            body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                abiCallResult(abiUint(schemaVersion))
            else -> rpcError(code = -32000, message = "unknown selector")
        }
    }
}

/**
 * Registriert eth_blockNumber + eth_getLogs am MockRpcServer für replay()-Tests.
 * [headBlock] ist der aktuelle Block-Head (als Dezimalzahl).
 * [logsJson] ist der JSON-Array-String der zurückzugebenden Logs.
 */
private fun registerEventsHandlers(
    server: MockRpcServer,
    headBlock: Long,
    logsJson: String,
) {
    val headHex = "\"0x${headBlock.toString(16)}\""
    server.onMethod("eth_blockNumber") { rpcSuccess(result = headHex) }
    server.onMethod("eth_getLogs") { rpcSuccess(result = logsJson) }
}

class ChainCommandTest :
    StringSpec({

        // ── kuml chain connect ────────────────────────────────────────────────

        "connect prints ContractIdentity fields" {
            val server = MockRpcServer()
            registerConnectHandlers(server, "42", "ipfs://QmTest", 3)
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "connect --rpc ${server.baseUrl()} --contract $TEST_CONTRACT",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain TEST_CONTRACT
                result.output shouldContain "ipfs://QmTest"
                result.output shouldContain "schemaVersion: 3"
                // modelHash ends with "42" (last byte = 0x42)
                result.output shouldContain "42"
            } finally {
                server.stop()
            }
        }

        "connect surfaces full modelHash hex in output" {
            val server = MockRpcServer()
            val distinctHashHex = "deadbeefcafebabe0102030405060708090a0b0c0d0e0f101112131415161718"
            registerConnectHandlers(server, distinctHashHex, "ipfs://QmAbc", 1)
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "connect --rpc ${server.baseUrl()} --contract $TEST_CONTRACT",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain distinctHashHex
            } finally {
                server.stop()
            }
        }

        "connect invalid RPC scheme yields CHAIN_CONNECT_ERROR" {
            // Production factory — SSRF guard active
            val result =
                ChainCommand { EvmChainAdapter() }.test(
                    "connect --rpc file:///etc/passwd --contract $TEST_CONTRACT",
                )
            result.statusCode shouldBe 51
            result.stderr shouldContain "Invalid chain connection parameters"
        }

        "connect blocked private IP yields CHAIN_CONNECT_ERROR" {
            // Production factory — SSRF guard rejects 127.0.0.1
            val result =
                ChainCommand { EvmChainAdapter() }.test(
                    "connect --rpc http://127.0.0.1:8545 --contract $TEST_CONTRACT",
                )
            result.statusCode shouldBe 51
        }

        "connect invalid contract address format yields CHAIN_CONNECT_ERROR" {
            val server = MockRpcServer()
            // Register nothing — adapter will reject address before any network call
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "connect --rpc ${server.baseUrl()} --contract 0xabc",
                    )
                result.statusCode shouldBe 51
                result.stderr shouldContain "Invalid chain connection parameters"
            } finally {
                server.stop()
            }
        }

        "connect network unreachable yields CHAIN_CONNECT_ERROR" {
            // Start server to obtain a free port, then stop it immediately
            val server = MockRpcServer()
            server.start()
            val url = server.baseUrl()
            server.stop()
            val result =
                ChainCommand(testAdapterFactory()).test(
                    "connect --rpc $url --contract $TEST_CONTRACT",
                )
            result.statusCode shouldBe 51
            result.stderr shouldContain "Could not connect"
        }

        // ── kuml chain verify ─────────────────────────────────────────────────

        "verify matching hash exits 0 and prints MATCH" {
            val modelSource = "diagram(\"test\") { }\n"
            val localHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(modelSource))
            val hashHex = localHash.joinToString("") { "%02x".format(it) }

            val modelFile = Files.createTempFile("kuml-verify-test", ".kuml.kts").toFile()
            modelFile.writeText(modelSource)

            val server = MockRpcServer()
            registerConnectHandlers(server, hashHex, "ipfs://QmMatch", 1)
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "verify --rpc ${server.baseUrl()} --contract $TEST_CONTRACT ${modelFile.absolutePath}",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "MATCH"
            } finally {
                server.stop()
                modelFile.delete()
            }
        }

        "verify mismatching hash exits 50 and prints MISMATCH" {
            val modelSource = "diagram(\"real\") { }\n"
            val modelFile = Files.createTempFile("kuml-verify-mismatch", ".kuml.kts").toFile()
            modelFile.writeText(modelSource)

            val server = MockRpcServer()
            // Different bytes32 → mismatch guaranteed
            registerConnectHandlers(server, "ff".repeat(32), "ipfs://QmMismatch", 1)
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "verify --rpc ${server.baseUrl()} --contract $TEST_CONTRACT ${modelFile.absolutePath}",
                    )
                result.statusCode shouldBe 50
                result.stderr shouldContain "MISMATCH"
            } finally {
                server.stop()
                modelFile.delete()
            }
        }

        "verify nonexistent file yields IO_ERROR without connecting" {
            // No server needed — file is read first
            val result =
                ChainCommand(testAdapterFactory()).test(
                    "verify --rpc http://unused:9999 --contract $TEST_CONTRACT /no/such/file.kuml.kts",
                )
            result.statusCode shouldBe 3
            result.stderr shouldContain "I/O error reading"
        }

        "verify prints both Local hash and On-chain hash" {
            val modelSource = "diagram(\"both\") { }\n"
            val localHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(modelSource))
            val hashHex = localHash.joinToString("") { "%02x".format(it) }

            val modelFile = Files.createTempFile("kuml-verify-both", ".kuml.kts").toFile()
            modelFile.writeText(modelSource)

            val server = MockRpcServer()
            registerConnectHandlers(server, hashHex, "ipfs://QmBoth", 1)
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "verify --rpc ${server.baseUrl()} --contract $TEST_CONTRACT ${modelFile.absolutePath}",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "Local hash:"
                result.output shouldContain "On-chain hash:"
            } finally {
                server.stop()
                modelFile.delete()
            }
        }

        // ── kuml chain events ─────────────────────────────────────────────────

        "events lists events up to limit" {
            val server = MockRpcServer()
            registerConnectHandlers(server, "00", "ipfs://QmEvents", 1)
            registerEventsHandlers(
                server,
                headBlock = 10L,
                logsJson =
                    """[
                    {"topics":["0xaaa"],"data":"0x01","blockNumber":"0x1","transactionHash":"0xtx1"},
                    {"topics":["0xbbb"],"data":"0x02","blockNumber":"0x2","transactionHash":"0xtx2"},
                    {"topics":["0xccc"],"data":"0x03","blockNumber":"0x3","transactionHash":"0xtx3"}
                ]""",
            )
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "events --rpc ${server.baseUrl()} --contract $TEST_CONTRACT --limit 3",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "block 1"
                result.output shouldContain "block 2"
                result.output shouldContain "block 3"
                result.output shouldContain "Total: 3 event(s)."
            } finally {
                server.stop()
            }
        }

        "events --from-block filter passed through to replay" {
            val server = MockRpcServer()
            registerConnectHandlers(server, "00", "ipfs://QmFromBlock", 1)
            // Return only a block-5 event regardless of request (mock doesn't filter)
            registerEventsHandlers(
                server,
                headBlock = 10L,
                logsJson =
                    """[
                    {"topics":["0xddd"],"data":"0x04","blockNumber":"0x5","transactionHash":"0xtx5"}
                ]""",
            )
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "events --rpc ${server.baseUrl()} --contract $TEST_CONTRACT --from-block 5 --limit 10",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "block 5"
                result.output shouldNotContain "block 1"
            } finally {
                server.stop()
            }
        }

        "events --limit 0 shows no events without calling replay" {
            val server = MockRpcServer()
            registerConnectHandlers(server, "00", "ipfs://QmZero", 1)
            // eth_blockNumber and getLogs are NOT registered — limit 0 must short-circuit before replay
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "events --rpc ${server.baseUrl()} --contract $TEST_CONTRACT --limit 0",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "(no events)"
            } finally {
                server.stop()
            }
        }

        "events negative --from-block rejected with CHAIN_CONNECT_ERROR" {
            val result =
                ChainCommand(testAdapterFactory()).test(
                    "events --rpc http://unused:9999 --contract $TEST_CONTRACT --from-block -1",
                )
            result.statusCode shouldBe 51
            result.stderr shouldContain ">= 0"
        }

        "events empty server response shows no events" {
            val server = MockRpcServer()
            registerConnectHandlers(server, "00", "ipfs://QmEmpty", 1)
            registerEventsHandlers(server, headBlock = 10L, logsJson = "[]")
            server.start()
            try {
                val result =
                    ChainCommand(testAdapterFactory()).test(
                        "events --rpc ${server.baseUrl()} --contract $TEST_CONTRACT",
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "(no events)"
            } finally {
                server.stop()
            }
        }
    })
