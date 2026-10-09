package dev.kuml.desktop.mcp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpRequestHandler
import dev.kuml.mcp.McpServerInfo
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.tools.McpTool
import dev.kuml.mcp.tools.McpToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class DesktopMcpServerTest :
    FunSpec({
        val token = "A".repeat(43)
        val echo =
            object : McpTool {
                override val descriptor =
                    McpToolDescriptor(name = "echo", description = "echo", inputSchema = buildJsonObject { put("type", "object") })

                override fun call(arguments: JsonObject): List<McpContent> = listOf(McpContent(type = "text", text = "pong"))
            }
        val handler =
            McpRequestHandler(
                registry = McpToolRegistry(tools = listOf(echo)),
                serverInfo = McpServerInfo(name = "test", version = "1"),
                protocolVersion = "2025-03-26",
            )
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""

        var server: DesktopMcpServer? = null

        fun startServer(maxBody: Int = 1024): Int {
            val s = DesktopMcpServer(handler = handler, tokenProvider = { token }, maxBodyBytes = maxBody)
            server = s
            return s.start(preferredPort = 0).port
        }

        afterTest { server?.stop() }

        fun auth(extra: List<Pair<String, String>> = emptyList()) =
            listOf("Authorization" to "Bearer $token", "Content-Type" to "application/json") + extra

        fun hostOf(port: Int) = "Host" to "127.0.0.1:$port"

        test("valid token on initialize yields 200 JSON") {
            val port = startServer()
            val r = RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = initialize.toByteArray())
            r.status shouldBe 200
            r.headers["content-type"]!! shouldContain "application/json"
            r.body shouldContain "\"protocolVersion\":\"2025-03-26\""
        }

        test("tools/call works end to end") {
            val port = startServer()
            val body = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{}}}"""
            val r = RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = body.toByteArray())
            r.status shouldBe 200
            r.body shouldContain "pong"
        }

        test("missing Authorization yields 401 with WWW-Authenticate") {
            val port = startServer()
            val r =
                RawHttp.send(
                    port = port,
                    headers = listOf(hostOf(port), "Content-Type" to "application/json"),
                    body = initialize.toByteArray(),
                )
            r.status shouldBe 401
            r.headers["www-authenticate"] shouldBe "Bearer"
        }

        test("wrong token (same length, shorter and longer) yields 401") {
            val port = startServer()
            listOf("B".repeat(43), "A".repeat(10), "A".repeat(80), "").forEach { bad ->
                val r =
                    RawHttp.send(
                        port = port,
                        headers = listOf(hostOf(port), "Authorization" to "Bearer $bad", "Content-Type" to "application/json"),
                        body = initialize.toByteArray(),
                    )
                r.status shouldBe 401
            }
        }

        test("non-Bearer scheme yields 401, lowercase bearer scheme is accepted") {
            val port = startServer()
            val basic =
                RawHttp.send(
                    port = port,
                    headers = listOf(hostOf(port), "Authorization" to "Basic $token", "Content-Type" to "application/json"),
                    body = initialize.toByteArray(),
                )
            basic.status shouldBe 401
            val lower =
                RawHttp.send(
                    port = port,
                    headers = listOf(hostOf(port), "Authorization" to "bearer $token", "Content-Type" to "application/json"),
                    body = initialize.toByteArray(),
                )
            lower.status shouldBe 200
        }

        test("foreign Host header yields 403") {
            val port = startServer()
            RawHttp.send(port = port, headers = auth(listOf("Host" to "evil.com:$port")), body = initialize.toByteArray()).status shouldBe
                403
            RawHttp
                .send(
                    port = port,
                    headers = auth(listOf("Host" to "127.0.0.1:${port + 1}")),
                    body = initialize.toByteArray(),
                ).status shouldBe
                403
            RawHttp.send(port = port, headers = auth(listOf("Host" to "127.0.0.1")), body = initialize.toByteArray()).status shouldBe 403
        }

        test("localhost Host header (any case) is accepted") {
            val port = startServer()
            RawHttp.send(port = port, headers = auth(listOf("Host" to "LocalHost:$port")), body = initialize.toByteArray()).status shouldBe
                200
        }

        test("Origin: foreign yields 403, localhost accepted, absent accepted") {
            val port = startServer()
            RawHttp
                .send(port = port, headers = auth(listOf(hostOf(port), "Origin" to "http://evil.com")), body = initialize.toByteArray())
                .status shouldBe 403
            RawHttp
                .send(
                    port = port,
                    headers = auth(listOf(hostOf(port), "Origin" to "http://localhost:$port")),
                    body = initialize.toByteArray(),
                ).status shouldBe 200
            RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = initialize.toByteArray()).status shouldBe 200
        }

        test("body larger than the cap with Content-Length yields 413") {
            val port = startServer(maxBody = 1024)
            val r =
                RawHttp.send(
                    port = port,
                    headers = auth(listOf(hostOf(port))),
                    body = ByteArray(1100) { 'x'.code.toByte() },
                    declaredLength = 2048,
                )
            r.status shouldBe 413
        }

        test("chunked body larger than the cap yields 413") {
            val port = startServer(maxBody = 1024)
            val r =
                RawHttp.send(
                    port = port,
                    headers = auth(listOf(hostOf(port))),
                    body = ByteArray(1025) { 'x'.code.toByte() },
                    chunked = true,
                )
            r.status shouldBe 413
        }

        test("chunked body within the cap is processed") {
            val port = startServer(maxBody = 1024)
            val r = RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = initialize.toByteArray(), chunked = true)
            r.status shouldBe 200
        }

        test("oversized body without a token yields 401, not 413 (auth precedes body handling)") {
            val port = startServer(maxBody = 1024)
            val r =
                RawHttp.send(
                    port = port,
                    headers = listOf(hostOf(port), "Content-Type" to "application/json"),
                    body = ByteArray(1100) { 'x'.code.toByte() },
                    declaredLength = 2048,
                )
            r.status shouldBe 401
        }

        test("GET, DELETE and OPTIONS yield 405 with Allow: POST") {
            val port = startServer()
            listOf("GET", "DELETE", "OPTIONS", "PUT").forEach { method ->
                val r = RawHttp.send(port = port, method = method, headers = auth(listOf(hostOf(port))))
                r.status shouldBe 405
                r.headers["allow"] shouldBe "POST"
            }
        }

        test("other paths yield 404 (root and prefix lookalikes)") {
            val port = startServer()
            listOf("/", "/mcpx", "/mcp/", "/mcp/extra").forEach { path ->
                RawHttp
                    .send(
                        port = port,
                        path = path,
                        headers = auth(listOf(hostOf(port))),
                        body = initialize.toByteArray(),
                    ).status shouldBe
                    404
            }
        }

        test("non-JSON content type yields 415") {
            val port = startServer()
            val r =
                RawHttp.send(
                    port = port,
                    headers = listOf(hostOf(port), "Authorization" to "Bearer $token", "Content-Type" to "text/plain"),
                    body = initialize.toByteArray(),
                )
            r.status shouldBe 415
        }

        test("content type with charset parameter is accepted") {
            val port = startServer()
            val r =
                RawHttp.send(
                    port = port,
                    headers = listOf(hostOf(port), "Authorization" to "Bearer $token", "Content-Type" to "application/json; charset=utf-8"),
                    body = initialize.toByteArray(),
                )
            r.status shouldBe 200
        }

        test("notification yields 202 without a body") {
            val port = startServer()
            val r =
                RawHttp.send(
                    port = port,
                    headers = auth(listOf(hostOf(port))),
                    body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""".toByteArray(),
                )
            r.status shouldBe 202
            r.body shouldBe ""
        }

        test("JSON-RPC batch yields 400 / -32600") {
            val port = startServer()
            val r = RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = "[$initialize]".toByteArray())
            r.status shouldBe 400
            r.body shouldContain "-32600"
        }

        test("non-primitive method or malformed params never yield HTTP 500") {
            val port = startServer()
            listOf(
                """{"jsonrpc":"2.0","id":1,"method":{}}""",
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":{},"arguments":{}}}""",
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":[]}}""",
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":[]}""",
            ).forEach { body ->
                val r = RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = body.toByteArray())
                r.status shouldBe 200
                r.body shouldContain "error"
            }
        }

        test("malformed JSON yields 400 / -32700") {
            val port = startServer()
            val r = RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = "{oops".toByteArray())
            r.status shouldBe 400
            r.body shouldContain "-32700"
        }

        test("server is bound to exactly 127.0.0.1") {
            startServer()
            val address = server!!.boundAddress!!
            address.address.isLoopbackAddress shouldBe true
            address.address.hostAddress shouldBe "127.0.0.1"
        }

        test("a taken preferred port falls back to another port and reports portChanged") {
            ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { blocker ->
                val s = DesktopMcpServer(handler = handler, tokenProvider = { token })
                server = s
                val result = s.start(preferredPort = blocker.localPort)
                result.portChanged shouldBe true
                (result.port != blocker.localPort) shouldBe true
            }
        }

        test("a free preferred port is kept") {
            val probe = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            val free = probe.localPort
            probe.close()
            val s = DesktopMcpServer(handler = handler, tokenProvider = { token })
            server = s
            val result = s.start(preferredPort = free)
            result.port shouldBe free
            result.portChanged shouldBe false
        }

        test("stop() refuses new connections and leaves no MCP threads behind") {
            val port = startServer()
            RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = initialize.toByteArray()).status shouldBe 200
            server!!.stop()
            var refused = false
            try {
                Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).close()
            } catch (_: ConnectException) {
                refused = true
            }
            refused shouldBe true

            fun lingering() =
                Thread.getAllStackTraces().keys.filter {
                    it.isAlive && (it.name.startsWith("kuml-mcp-http-") || it.name == "HTTP-Dispatcher")
                }
            val deadline = System.currentTimeMillis() + 5_000
            while (lingering().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            lingering() shouldBe emptyList()
        }

        test("worker threads are daemon threads") {
            val port = startServer()
            RawHttp.send(port = port, headers = auth(listOf(hostOf(port))), body = initialize.toByteArray())
            val workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("kuml-mcp-http-") }
            workers.isNotEmpty() shouldBe true
            workers.all { it.isDaemon } shouldBe true
        }

        test("logs never contain the token or request bodies") {
            val logger = LoggerFactory.getLogger("dev.kuml.desktop.mcp") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val oldLevel = logger.level
            logger.level = ch.qos.logback.classic.Level.ALL
            try {
                val port = startServer()
                val marker = "SECRET-BODY-MARKER"
                RawHttp.send(
                    port = port,
                    headers = auth(listOf(hostOf(port))),
                    body =
                        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"x":"$marker"}}}"""
                            .toByteArray(),
                )
                RawHttp.send(
                    port = port,
                    headers = listOf(hostOf(port), "Authorization" to "Bearer $token-wrong", "Content-Type" to "application/json"),
                    body = marker.toByteArray(),
                )
                server!!.stop()
                val lines = appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
                lines.isNotEmpty() shouldBe true
                lines.none { it.contains(token) } shouldBe true
                lines.none { it.contains(marker) } shouldBe true
                lines.any { it.contains("tool=echo") } shouldBe true
            } finally {
                logger.detachAppender(appender)
                logger.level = oldLevel
            }
        }

        test("blank or malformed expected token fails closed, empty bearer gets 401") {
            for (expected in listOf("", "short")) {
                val s = DesktopMcpServer(handler = handler, tokenProvider = { expected })
                server = s
                val port = s.start(preferredPort = 0).port
                for (bearer in listOf("Bearer \u00A0", "Bearer $expected")) {
                    val r =
                        RawHttp.send(
                            port = port,
                            headers = listOf("Authorization" to bearer, "Content-Type" to "application/json", hostOf(port)),
                            body = initialize.toByteArray(),
                        )
                    r.status shouldBe 401
                }
                s.stop()
            }
        }
    })
