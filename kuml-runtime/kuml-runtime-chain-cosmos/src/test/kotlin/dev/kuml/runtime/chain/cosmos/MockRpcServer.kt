package dev.kuml.runtime.chain.cosmos

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Leichtgewichtiger in-process JSON-RPC-Mock-Server auf Basis von
 * `com.sun.net.httpserver.HttpServer` (Teil des JDK, kein Extra-Dep).
 *
 * Unterstützt sowohl JSON-RPC POST-Requests (über [onMethod]) als auch
 * einfache GET-Requests (über [onGet]).
 */
class MockRpcServer {
    // Handlers are written by the test thread and read by HttpServer executor threads.
    private val handlers = ConcurrentHashMap<String, (String) -> String>()
    private val getHandlers = CopyOnWriteArrayList<Pair<String, (String) -> String>>()
    private lateinit var server: HttpServer
    private var executor: ExecutorService? = null

    /** Removes all registered handlers so one server can be reused across tests of a spec. */
    fun reset() {
        handlers.clear()
        getHandlers.clear()
    }

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
        getHandlers.removeIf { it.first == pathPrefix }
        getHandlers.add(pathPrefix to handler)
    }

    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val isGet = exchange.requestMethod.equals("GET", ignoreCase = true)
            if (isGet) {
                val uri = exchange.requestURI.toString()
                val handler = getHandlers.firstOrNull { uri.startsWith(it.first) }?.second
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
        executor = Executors.newFixedThreadPool(2)
        server.executor = executor
        server.start()
    }

    fun stop() {
        server.stop(1)
        executor?.shutdownNow()
    }

    fun baseUrl(): String = "http://${server.address.address.hostAddress}:${server.address.port}"

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
