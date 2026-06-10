package dev.kuml.cli.run

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.BindException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * MCP HTTP adapter for `kuml run --adapter mcp`.
 *
 * Starts a lightweight [HttpServer] (JDK standard API, no extra deps)
 * with 5 REST endpoints:
 *
 *  - `POST /run/event` — fire an event
 *  - `GET /run/snapshot` — current state info
 *  - `POST /run/patch` — update variables / force-state
 *  - `POST /run/stop` — terminate and shutdown server
 *  - `GET /run/health` — health check
 *
 * Binds to [requestedPort] (0 = OS-assigned random port).
 */
@Suppress("DEPRECATION") // com.sun.net.httpserver is intentional JDK API usage
internal class McpHttpAdapter(
    private val manager: RunSessionManager,
    private val requestedPort: Int = 0,
) {
    private lateinit var server: HttpServer
    private val stopLatch = CountDownLatch(1)
    private val json = Json { ignoreUnknownKeys = true }

    /** Starts the HTTP server. Returns the bound port. */
    fun start(): Int {
        try {
            server = HttpServer.create(InetSocketAddress(requestedPort), 0)
        } catch (_: BindException) {
            System.err.println("kuml run: port $requestedPort is busy")
            throw McpPortBusyException(requestedPort)
        }
        server.executor = Executors.newCachedThreadPool()

        server.createContext("/run/event") { exchange -> handleEvent(exchange) }
        server.createContext("/run/snapshot") { exchange -> handleSnapshot(exchange) }
        server.createContext("/run/patch") { exchange -> handlePatch(exchange) }
        server.createContext("/run/stop") { exchange -> handleStop(exchange) }
        server.createContext("/run/health") { exchange -> handleHealth(exchange) }

        server.start()
        return (server.address as InetSocketAddress).port
    }

    /** Blocks until `POST /run/stop` is called or the session terminates. */
    fun awaitTermination() {
        stopLatch.await()
    }

    /** Stops the server immediately. */
    fun stop() {
        server.stop(0)
        stopLatch.countDown()
    }

    // ── Endpoint handlers ────────────────────────────────────────────────────

    private fun handleEvent(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"error":"method not allowed"}""")
            return
        }
        val body = exchange.requestBody.bufferedReader().readText()
        val (eventName, payload) =
            try {
                val obj = json.parseToJsonElement(body).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: ""
                val payloadObj = obj["payload"]?.jsonObject ?: JsonObject(emptyMap())
                val payloadMap = jsonObjectToMap(payloadObj)
                name to payloadMap
            } catch (e: Exception) {
                sendJson(exchange, 400, """{"error":"invalid JSON: ${e.message?.replace("\"", "\\\"")}"}""")
                return
            }

        if (eventName.isEmpty()) {
            sendJson(exchange, 400, """{"error":"'name' field is required"}""")
            return
        }

        val result = manager.event(eventName, payload)
        val responseBody =
            when (result) {
                is SessionResult.Ok ->
                    """{"fired":true,"activeStates":${result.activeStates.asJsonArray()},"traceDelta":${result.traceDelta.size},"message":${jsonString(
                        result.message,
                    )}}"""

                is SessionResult.Terminated ->
                    """{"fired":false,"terminated":true,"totalSteps":${result.totalSteps},"message":"${result.message}"}"""

                is SessionResult.Error ->
                    """{"error":${jsonString(result.message)}}"""
            }
        val code = if (result is SessionResult.Error) 422 else 200
        sendJson(exchange, code, responseBody)
    }

    private fun handleSnapshot(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"error":"method not allowed"}""")
            return
        }
        val result = manager.snapshot()
        val responseBody =
            when (result) {
                is SessionResult.Ok ->
                    """{"activeStates":${result.activeStates.asJsonArray()},"stepInfo":${jsonString(result.message)}}"""

                is SessionResult.Terminated ->
                    """{"terminated":true,"totalSteps":${result.totalSteps}}"""

                is SessionResult.Error ->
                    """{"error":${jsonString(result.message)}}"""
            }
        sendJson(exchange, 200, responseBody)
    }

    private fun handlePatch(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"error":"method not allowed"}""")
            return
        }
        val body = exchange.requestBody.bufferedReader().readText()
        val (variables, forceState) =
            try {
                val obj = json.parseToJsonElement(body).jsonObject
                val vars = obj["variables"]?.jsonObject?.let { jsonObjectToMap(it) } ?: emptyMap()
                val fs = obj["forceState"]?.jsonPrimitive?.content
                vars to fs
            } catch (e: Exception) {
                sendJson(exchange, 400, """{"error":"invalid JSON: ${e.message?.replace("\"", "\\\"")}"}""")
                return
            }

        val result = manager.patch(variables, forceState)
        val responseBody =
            when (result) {
                is SessionResult.Ok ->
                    """{"ok":true,"activeStates":${result.activeStates.asJsonArray()}}"""

                is SessionResult.Terminated ->
                    """{"ok":false,"terminated":true,"totalSteps":${result.totalSteps}}"""

                is SessionResult.Error ->
                    """{"ok":false,"error":${jsonString(result.message)}}"""
            }
        val code = if (result is SessionResult.Error) 422 else 200
        sendJson(exchange, code, responseBody)
    }

    private fun handleStop(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"error":"method not allowed"}""")
            return
        }
        val result = manager.stop()
        val totalSteps =
            when (result) {
                is SessionResult.Terminated -> result.totalSteps
                else -> 0L
            }
        sendJson(exchange, 200, """{"totalSteps":$totalSteps}""")
        // Shutdown server on a separate thread to allow the response to be flushed
        Thread {
            Thread.sleep(100)
            stop()
        }.also { it.isDaemon = true }.start()
    }

    private fun handleHealth(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"error":"method not allowed"}""")
            return
        }
        val kind =
            when (manager.currentSession) {
                is RunSession.Stm -> "stm"
                is RunSession.Act -> "act"
                null -> "none"
            }
        sendJson(
            exchange,
            200,
            """{"status":"ok","kind":"$kind","version":${jsonString(KumlVersion.version)}}""",
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sendJson(
        exchange: HttpExchange,
        statusCode: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun jsonObjectToMap(obj: JsonObject): Map<String, Any> =
        obj.mapValues { (_, v) ->
            when {
                v is JsonPrimitive && v.booleanOrNull != null -> v.boolean
                v is JsonPrimitive && v.intOrNull != null -> v.int
                v is JsonPrimitive && v.doubleOrNull != null -> v.double
                v is JsonPrimitive -> v.content
                else -> v.toString()
            }
        }

    private fun jsonString(value: String?): String =
        if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun List<String>.asJsonArray(): String = joinToString(",", "[", "]") { "\"$it\"" }
}

internal class McpPortBusyException(
    port: Int,
) : RuntimeException("Port $port is busy") {
    val exitCode: Int = ExitCodes.RUN_PORT_BUSY
}
