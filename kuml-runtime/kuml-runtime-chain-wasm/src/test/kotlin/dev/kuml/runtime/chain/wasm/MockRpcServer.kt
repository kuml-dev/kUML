package dev.kuml.runtime.chain.wasm

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Leichtgewichtiger in-process JSON-RPC-Mock-Server fuer WASM-Adapter-Tests.
 * Basiert auf com.sun.net.httpserver.HttpServer (Teil des JDK, kein Extra-Dep).
 */
class MockRpcServer {
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

fun rpcSuccess(result: String): String = """{"jsonrpc":"2.0","id":1,"result":$result}"""

fun rpcError(
    code: Int,
    message: String,
): String = """{"jsonrpc":"2.0","id":1,"error":{"code":$code,"message":"$message"}}"""
