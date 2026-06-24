package dev.kuml.runtime.chain.cosmos

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Leichtgewichtiger in-process JSON-RPC-Mock-Server auf Basis von
 * `com.sun.net.httpserver.HttpServer` (Teil des JDK, kein Extra-Dep).
 *
 * Unterstützt sowohl JSON-RPC POST-Requests (über [onMethod]) als auch
 * einfache GET-Requests (über [onGet]).
 */
class MockRpcServer {
    private val handlers = mutableMapOf<String, (String) -> String>()
    private val getHandlers = mutableMapOf<String, (String) -> String>()
    private lateinit var server: HttpServer

    fun onMethod(
        method: String,
        handler: (requestBody: String) -> String,
    ) {
        handlers[method] = handler
    }

    /**
     * Registriert einen Handler für GET-Requests auf dem angegebenen Pfad-Präfix.
     * [pathPrefix] wird als Startwert des Request-URI-Pfads verglichen.
     */
    fun onGet(
        pathPrefix: String,
        handler: (requestUri: String) -> String,
    ) {
        getHandlers[pathPrefix] = handler
    }

    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val isGet = exchange.requestMethod.equals("GET", ignoreCase = true)
            if (isGet) {
                val uri = exchange.requestURI.toString()
                val handler = getHandlers.entries.firstOrNull { uri.startsWith(it.key) }?.value
                val response =
                    handler?.invoke(uri)
                        ?: """{"code":5,"message":"not found"}"""
                val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                exchange.responseBody.use { it.write(responseBytes) }
            } else {
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

fun rpcSuccess(
    id: Long = 1,
    result: String,
): String = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

fun rpcError(
    id: Long = 1,
    code: Int,
    message: String,
): String = """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}"""
