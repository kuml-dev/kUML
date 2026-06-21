package dev.kuml.runtime.chain.move.sui

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Leichtgewichtiger in-process JSON-RPC-Mock-Server für Sui-Tests.
 * Identische Struktur wie EVM-MockRpcServer (method-basiertes Routing).
 */
class MockRpcServer {
    private val handlers = mutableMapOf<String, (String) -> String>()
    private lateinit var server: HttpServer

    /**
     * Registriert einen Handler für die angegebene JSON-RPC-Methode.
     * [handler] empfängt den rohen Request-Body und gibt den rohen Response-Body zurück.
     */
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

    /** Gibt die Basis-URL des Servers zurück (z.B. "http://127.0.0.1:54321"). */
    fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    private fun extractMethod(body: String): String {
        val match = Regex(""""method"\s*:\s*"([^"]+)"""").find(body)
        return match?.groupValues?.get(1) ?: "unknown"
    }
}

/** Hilfsfunktion: Erzeugt eine Standard-JSON-RPC-Erfolgsantwort. */
fun rpcSuccess(
    id: Long = 1,
    result: String,
): String = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

/** Hilfsfunktion: Erzeugt eine Standard-JSON-RPC-Fehlerantwort. */
fun rpcError(
    id: Long = 1,
    code: Int,
    message: String,
): String = """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}"""
