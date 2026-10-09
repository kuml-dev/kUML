package dev.kuml.desktop.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.kuml.mcp.McpRequestHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Result of [DesktopMcpServer.start]: the port actually bound, and whether it differs from the requested one. */
internal data class StartResult(
    val port: Int,
    val portChanged: Boolean,
)

/**
 * Streamable-HTTP transport (MCP spec 2025-03-26, POST-only profile) for the desktop app's
 * local MCP server, on top of the JDK's built-in `com.sun.net.httpserver`.
 *
 * Security model -- loopback only, bearer token, strict header validation:
 *  - bound to the literal address 127.0.0.1 (never `getLoopbackAddress()`, which can return `::1`);
 *  - one context `/` that itself matches the exact path `/mcp` (a `/mcp` context would also
 *    swallow `/mcpx`);
 *  - checks run in a fixed order and the first violation wins; the request body is not read
 *    until the token has been verified, so an unauthenticated client cannot make the server
 *    buffer anything;
 *  - `GET`/`DELETE`/`OPTIONS`/… are refused with 405, which also defeats every CORS preflight;
 *  - `Host` and `Origin` must be exactly `127.0.0.1:<port>` / `localhost:<port>` (DNS-rebinding
 *    defence); a missing `Origin` (non-browser clients) is allowed;
 *  - token compared with [MessageDigest.isEqual] (constant time);
 *  - body capped at 1 MiB (also when chunked), JSON-RPC batches refused.
 *
 * Known limitations (local, availability only): the `sun.net.httpserver.*` timeouts below are JVM-wide
 * and only apply if no other JDK HttpServer was loaded first; header reading runs on the 4 worker
 * threads, so a local process holding 4 slow connections can stall the server for up to the
 * request timeout (30 s) repeatedly.
 *
 * Logging never includes headers, tokens, bodies, scripts or exception messages -- only method,
 * path, status and (for `tools/call`) the tool name.
 */
internal class DesktopMcpServer(
    private val handler: McpRequestHandler,
    private val tokenProvider: () -> String?,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** The bound socket address, or `null` while stopped. */
    val boundAddress: InetSocketAddress? get() = server?.address

    /**
     * Binds 127.0.0.1:[preferredPort] (0 = any free port; values outside 1024..65535 count as 0).
     * If the preferred port is taken, falls back to an ephemeral port and reports
     * [StartResult.portChanged].
     */
    @Synchronized
    fun start(preferredPort: Int): StartResult {
        stop()
        val wanted = if (preferredPort in MIN_PORT..MAX_PORT) preferredPort else 0
        val created =
            try {
                HttpServer.create(InetSocketAddress(LOOPBACK, wanted), BACKLOG)
            } catch (e: BindException) {
                if (wanted == 0) throw e
                HttpServer.create(InetSocketAddress(LOOPBACK, 0), BACKLOG)
            }
        val port = created.address.port
        val pool = Executors.newFixedThreadPool(WORKER_THREADS, DaemonThreadFactory())
        created.executor = pool
        created.createContext("/") { exchange -> handle(exchange = exchange, port = port) }
        created.start()
        server = created
        executor = pool
        logger.info("MCP HTTP server listening on 127.0.0.1:{}", port)
        return StartResult(port = port, portChanged = wanted != 0 && port != wanted)
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        try {
            s.stop(1)
        } finally {
            executor?.shutdownNow()
            executor = null
            logger.info("MCP HTTP server stopped")
        }
    }

    private fun handle(
        exchange: HttpExchange,
        port: Int,
    ) {
        var status = 500
        var toolName: String? = null
        try {
            val outcome = process(exchange = exchange, port = port)
            status = outcome.status
            toolName = outcome.toolName
            respond(exchange = exchange, status = outcome.status, body = outcome.body, extraHeaders = outcome.headers)
        } catch (e: Exception) {
            // Class name only: messages may echo request content.
            logger.warn("MCP request failed with {}", e.javaClass.simpleName)
            status = 500
            runCatching { respond(exchange = exchange, status = 500, body = null, extraHeaders = emptyMap()) }
        } finally {
            exchange.close()
            logger.info(
                "MCP {} {} -> {}{}",
                sanitize(exchange.requestMethod),
                sanitize(exchange.requestURI.rawPath ?: ""),
                status,
                toolName?.let { " tool=$it" } ?: "",
            )
        }
    }

    private class Outcome(
        val status: Int,
        val body: ByteArray? = null,
        val headers: Map<String, String> = emptyMap(),
        val toolName: String? = null,
    )

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun process(
        exchange: HttpExchange,
        port: Int,
    ): Outcome {
        // 1. path
        if (exchange.requestURI.rawPath != MCP_PATH) return Outcome(status = 404)
        // 2. method -- everything but POST (incl. OPTIONS => CORS preflight fails)
        if (exchange.requestMethod != "POST") return Outcome(status = 405, headers = mapOf("Allow" to "POST"))
        val headers = exchange.requestHeaders
        // 3. Host
        val allowedHosts = setOf("127.0.0.1:$port", "localhost:$port")
        val host = headers.getFirst("Host")?.lowercase()
        if (host == null || host !in allowedHosts) return Outcome(status = 403)
        // 4. Origin (absent is fine)
        val origin = headers.getFirst("Origin")
        if (origin != null && origin !in setOf("http://127.0.0.1:$port", "http://localhost:$port")) return Outcome(status = 403)
        // 5. bearer token
        if (!authorized(headers.getFirst("Authorization"))) {
            return Outcome(status = 401, headers = mapOf("WWW-Authenticate" to "Bearer"))
        }
        // 6. size (declared)
        val declared = headers.getFirst("Content-Length")?.trim()?.toLongOrNull()
        if (declared != null && declared > maxBodyBytes) return Outcome(status = 413)
        // 7. content type
        val mediaType =
            headers
                .getFirst("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (mediaType != "application/json") return Outcome(status = 415)
        // 8. body
        val raw = exchange.requestBody.readNBytes(maxBodyBytes + 1)
        if (raw.size > maxBodyBytes) return Outcome(status = 413)
        val text = raw.toString(Charsets.UTF_8)

        val parsed =
            try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                return Outcome(status = 400, body = errorBody(code = -32700, message = "Parse error"), headers = JSON_HEADERS)
            }
        if (parsed is JsonArray) {
            return Outcome(
                status = 400,
                body = errorBody(code = -32600, message = "Batch requests are not supported"),
                headers = JSON_HEADERS,
            )
        }
        if (parsed !is JsonObject) {
            return Outcome(status = 400, body = errorBody(code = -32600, message = "Invalid Request"), headers = JSON_HEADERS)
        }
        val tool = toolNameOf(parsed)
        if (!parsed.containsKey("id")) {
            // Notification or client response: deliver, answer 202 without a body.
            handler.handle(text)
            return Outcome(status = 202, toolName = tool)
        }
        val response = handler.handle(text) ?: return Outcome(status = 202, toolName = tool)
        return Outcome(status = 200, body = response.toByteArray(Charsets.UTF_8), headers = JSON_HEADERS, toolName = tool)
    }

    private fun authorized(header: String?): Boolean {
        if (header == null) return false
        val space = header.indexOf(' ')
        if (space <= 0) return false
        if (!header.substring(0, space).equals("Bearer", ignoreCase = true)) return false
        // Fail closed: no token, or a token of the wrong shape, authorizes nothing (not even an empty bearer).
        val expectedToken = tokenProvider()?.takeIf { McpTokenStore.TOKEN_PATTERN.matches(it) } ?: return false
        val givenToken = header.substring(space + 1).trim()
        if (!McpTokenStore.TOKEN_PATTERN.matches(givenToken)) return false
        return MessageDigest.isEqual(givenToken.toByteArray(Charsets.UTF_8), expectedToken.toByteArray(Charsets.UTF_8))
    }

    private fun toolNameOf(message: JsonObject): String? {
        if ((message["method"] as? JsonPrimitive)?.content != "tools/call") return null
        val name = (message["params"] as? JsonObject)?.get("name") as? JsonPrimitive ?: return null
        return sanitize(name.content)
    }

    private fun sanitize(value: String): String = value.take(MAX_LOGGED_NAME).filter { it.isLetterOrDigit() || it in "._-/" }

    private fun errorBody(
        code: Int,
        message: String,
    ): ByteArray =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonNull)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString().toByteArray(Charsets.UTF_8)

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray?,
        extraHeaders: Map<String, String>,
    ) {
        extraHeaders.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
        exchange.responseHeaders.set("Cache-Control", "no-store")
        if (body == null || body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private class DaemonThreadFactory : ThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(r: Runnable): Thread = Thread(r, "kuml-mcp-http-${counter.getAndIncrement()}").also { it.isDaemon = true }
    }

    internal companion object {
        const val DEFAULT_MAX_BODY_BYTES: Int = 1 shl 20
        const val MCP_PATH = "/mcp"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val BACKLOG = 16
        private const val WORKER_THREADS = 4
        private const val MAX_LOGGED_NAME = 80
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val JSON_HEADERS = mapOf("Content-Type" to "application/json; charset=utf-8")
        private val logger = LoggerFactory.getLogger("dev.kuml.desktop.mcp")

        init {
            // sun.net.httpserver.ServerConfig reads these once per JVM, at class-load time of the
            // HttpServer implementation -- so they must be set before the first HttpServer.create.
            // User-provided values win.
            mapOf(
                "sun.net.httpserver.maxReqTime" to "30",
                "sun.net.httpserver.maxRspTime" to "60",
                "sun.net.httpserver.idleInterval" to "30",
            ).forEach { (k, v) -> if (System.getProperty(k) == null) System.setProperty(k, v) }
        }
    }
}
