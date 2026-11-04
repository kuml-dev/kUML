package dev.kuml.runtime.chain.move.aptos

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Leichtgewichtiger in-process REST-Mock-Server für Aptos-Tests.
 * Path-basiertes Routing (GET), kein JSON-RPC-Envelope.
 */
class MockRestServer {
    // Routes are written by the test thread and read by HttpServer executor threads.
    private val routes = CopyOnWriteArrayList<Pair<Regex, (HttpExchange) -> Pair<Int, String>>>()
    private lateinit var server: HttpServer
    private var executor: ExecutorService? = null

    /** Removes all registered routes so one server can be reused across tests of a spec. */
    fun reset() {
        routes.clear()
    }

    /**
     * Registriert einen Handler für Pfade, die auf [pattern] matchen.
     * [handler] empfängt den HttpExchange und gibt (statusCode, responseBody) zurück.
     * Erste passende Route gewinnt.
     */
    fun onPath(
        pattern: Regex,
        handler: (HttpExchange) -> Pair<Int, String>,
    ) {
        routes += pattern to handler
    }

    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val (status, body) =
                try {
                    ex.requestBody.use { it.readBytes() }
                    val pathWithQuery = ex.requestURI.path + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
                    val match = routes.firstOrNull { it.first.containsMatchIn(pathWithQuery) }
                    match?.second?.invoke(ex)
                        ?: (404 to """{"message":"not found","error_code":"not_found"}""")
                } catch (e: Exception) {
                    // Surface handler failures as a visible 500 instead of a connection closed without bytes.
                    500 to """{"message":"mock handler failed: ${e.javaClass.simpleName}","error_code":"mock_error"}"""
                }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
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
}
