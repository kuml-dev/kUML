package dev.kuml.runtime.chain.evm

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress

class EvmJsonRpcClientTest :
    StringSpec({

        "ethBlockNumber parses 0x-quantity from RPC result" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("eth_blockNumber") { rpcSuccess(result = "\"0x10\"") }
                server.start()
                try {
                    val client = EvmJsonRpcClient(server.baseUrl())
                    client.ethBlockNumber() shouldBe 16L
                } finally {
                    server.stop()
                }
            }
        }

        "call surfaces JSON-RPC error object as RpcError with code and message" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("eth_blockNumber") { rpcError(code = -32000, message = "boom") }
                server.start()
                try {
                    val client = EvmJsonRpcClient(server.baseUrl())
                    val ex =
                        shouldThrow<EvmChainAdapterException.RpcError> {
                            client.ethBlockNumber()
                        }
                    ex.code shouldBe -32000
                    ex.rpcMessage shouldBe "boom"
                } finally {
                    server.stop()
                }
            }
        }

        "non-2xx HTTP status maps to NetworkError" {
            runTest {
                val httpServer =
                    HttpServer.create(
                        InetSocketAddress(0),
                        0,
                    )
                httpServer.createContext("/") { exchange ->
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                }
                httpServer.start()
                try {
                    val client = EvmJsonRpcClient("http://127.0.0.1:${httpServer.address.port}")
                    shouldThrow<EvmChainAdapterException.NetworkError> {
                        client.ethBlockNumber()
                    }
                } finally {
                    httpServer.stop(0)
                }
            }
        }

        "ethGetLogs sends fromBlock toBlock address topics as hex in params" {
            runTest {
                var capturedBody = ""
                val server = MockRpcServer()
                server.onMethod("eth_getLogs") { body ->
                    capturedBody = body
                    rpcSuccess(result = "[]")
                }
                server.start()
                try {
                    val client = EvmJsonRpcClient(server.baseUrl())
                    client.ethGetLogs(
                        address = "0xdeadbeef",
                        fromBlock = 100L,
                        toBlock = 200L,
                        topics = listOf("0xabc"),
                    )
                    capturedBody shouldContain "0xdeadbeef"
                    capturedBody shouldContain "0x64" // 100 in hex
                    capturedBody shouldContain "0xc8" // 200 in hex
                    capturedBody shouldContain "0xabc"
                } finally {
                    server.stop()
                }
            }
        }

        "parseHexQuantity and toHexQuantity round-trip" {
            EvmJsonRpcClient.parseHexQuantity("0x0") shouldBe 0L
            EvmJsonRpcClient.parseHexQuantity("0xff") shouldBe 255L
            EvmJsonRpcClient.parseHexQuantity("0x10") shouldBe 16L
            EvmJsonRpcClient.toHexQuantity(0L) shouldBe "0x0"
            EvmJsonRpcClient.toHexQuantity(255L) shouldBe "0xff"
            EvmJsonRpcClient.toHexQuantity(16L) shouldBe "0x10"
            // Round-trip
            listOf(0L, 1L, 127L, 255L, 65535L, Long.MAX_VALUE / 2).forEach { v ->
                EvmJsonRpcClient.parseHexQuantity(EvmJsonRpcClient.toHexQuantity(v)) shouldBe v
            }
        }
    })
