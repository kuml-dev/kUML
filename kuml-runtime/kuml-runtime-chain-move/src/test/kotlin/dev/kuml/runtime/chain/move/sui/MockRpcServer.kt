package dev.kuml.runtime.chain.move.sui

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Leichtgewichtiger in-process JSON-RPC-Mock-Server für Sui-Tests.
 * Identische Struktur wie EVM-MockRpcServer (method-basiertes Routing).
 * Bindet nur an Loopback, bedient Requests über einen festen Zwei-Thread-Executor
 * und kann per [reset] über mehrere Tests einer Spec wiederverwendet werden.
 */
class MockRpcServer {
    // Handlers are written by the test thread and read by HttpServer executor threads.
    private val handlers = ConcurrentHashMap<String, (String) -> String>()
    private lateinit var server: HttpServer
    private var executor: ExecutorService? = null

    /** Removes all registered handlers so one server can be reused across tests of a spec. */
    fun reset() {
        handlers.clear()
    }

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
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val (status, response) =
                try {
                    val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    val method = extractMethod(body)
                    val handler = handlers[method]
                    200 to (
                        handler?.invoke(body)
                            ?: """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found: $method"}}"""
                    )
                } catch (e: Exception) {
                    // Surface handler failures as a visible 500 instead of a connection closed without bytes.
                    500 to """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"mock handler failed: ${e.javaClass.simpleName}"}}"""
                }
            val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        executor = Executors.newFixedThreadPool(2)
        server.executor = executor
        server.start()
    }

    fun stop() {
        server.stop(1)
        executor?.shutdownNow()
    }

    /** Gibt die Basis-URL des Servers zurück (z.B. "http://127.0.0.1:54321"). */
    fun baseUrl(): String = "http://${server.address.address.hostAddress}:${server.address.port}"

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
