package dev.kuml.runtime.chain.move.aptos

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Leichtgewichtiger in-process REST-Mock-Server für Aptos-Tests.
 * Path-basiertes Routing (GET), kein JSON-RPC-Envelope.
 */
class MockRestServer {
    private val routes = mutableListOf<Pair<Regex, (HttpExchange) -> Pair<Int, String>>>()
    private lateinit var server: HttpServer

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
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            val pathWithQuery = ex.requestURI.path + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            val match = routes.firstOrNull { it.first.containsMatchIn(pathWithQuery) }
            val (status, body) =
                match?.second?.invoke(ex)
                    ?: (
                        404 to
                            """{"message":"not found","error_code":"not_found"}"""
                    )
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    /** Gibt die Basis-URL des Servers zurück (z.B. "http://127.0.0.1:54321"). */
    fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"
}
