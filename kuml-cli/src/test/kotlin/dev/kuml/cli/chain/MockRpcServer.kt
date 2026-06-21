package dev.kuml.cli.chain

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Leichtgewichtiger in-process JSON-RPC-Mock-Server auf Basis von
 * `com.sun.net.httpserver.HttpServer` (Teil des JDK, kein Extra-Dep).
 *
 * Kopiert aus `kuml-runtime-chain-evm` test sources — dort nicht per
 * java-test-fixtures-Plugin exponiert (Repo-Konvention: kein cross-module
 * test-fixtures-Sharing). Diese Kopie ist bewusst — die Datei ist 80 Zeilen
 * reiner JDK-Code ohne schützenswerte Logik.
 */
internal class MockRpcServer {
    private val handlers = mutableMapOf<String, (String) -> String>()

    private lateinit var server: HttpServer

    fun onMethod(
        method: String,
        handler: (requestBody: String) -> String,
    ) {
        handlers[method] = handler
    }

    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val method = extractMethod(body)
            val handler = handlers[method]
            val response =
                handler?.invoke(body)
                    ?: """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found: $method"}}"""
            val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.executor = null
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    private fun extractMethod(body: String): String {
        val match = Regex(""""method"\s*:\s*"([^"]+)"""").find(body)
        return match?.groupValues?.get(1) ?: "unknown"
    }
}

internal fun rpcSuccess(
    id: Long = 1,
    result: String,
): String = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

internal fun rpcError(
    id: Long = 1,
    code: Int,
    message: String,
): String = """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}"""

// ── ABI encoding helpers ──────────────────────────────────────────────────────

/** Encodes a fixed 32-byte slot (bytes32 / uint256) — returns hex without 0x. */
internal fun abiBytes32(hex: String): String = hex.padStart(64, '0')

/** ABI-encodes a uint256 value. */
internal fun abiUint(value: Int): String = value.toString(16).padStart(64, '0')

/** ABI-encodes a dynamic string (offset=0x20, length, data padded to 32-byte boundary). */
internal fun abiString(s: String): String {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val offset = "0000000000000000000000000000000000000000000000000000000000000020"
    val length = bytes.size.toString(16).padStart(64, '0')
    val hexData = bytes.joinToString("") { "%02x".format(it) }
    val padded = hexData.padEnd(((hexData.length + 63) / 64) * 64, '0')
    return offset + length + (if (padded.isEmpty()) "0".repeat(64) else padded)
}

/** Returns an ABI-encoded eth_call result prefixed with "0x". */
internal fun abiCallResult(hex: String): String = rpcSuccess(result = "\"0x$hex\"")
