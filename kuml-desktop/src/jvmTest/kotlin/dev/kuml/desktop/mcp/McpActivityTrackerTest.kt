package dev.kuml.desktop.mcp

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpRequestHandler
import dev.kuml.mcp.McpServerInfo
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.tools.McpTool
import dev.kuml.mcp.tools.McpToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpActivityTrackerTest :
    FunSpec({
        test("phase goes Ready -> Busy -> Ready on success") {
            val t = McpActivityTracker(clock = { 1L })
            t.phase shouldBe McpActivityPhase.Ready
            val id = t.begin(label = "kuml_render")
            t.phase shouldBe McpActivityPhase.Busy
            t.end(id = id, ok = true)
            t.phase shouldBe McpActivityPhase.Ready
            t.recent.single().ok shouldBe true
        }

        test("a failed call keeps the phase Failed until the next success") {
            val t = McpActivityTracker(clock = { 1L })
            t.end(id = t.begin(label = "a"), ok = false)
            t.phase shouldBe McpActivityPhase.Failed
            t.end(id = t.begin(label = "b"), ok = true)
            t.phase shouldBe McpActivityPhase.Ready
        }

        test("overlapping calls stay Busy until the last one ends") {
            val t = McpActivityTracker(clock = { 1L })
            val a = t.begin(label = "a")
            val b = t.begin(label = "b")
            t.end(id = a, ok = true)
            t.phase shouldBe McpActivityPhase.Busy
            t.end(id = b, ok = true)
            t.phase shouldBe McpActivityPhase.Ready
        }

        test("recent keeps only the newest entries, newest first, and finishedCounter changes") {
            val t = McpActivityTracker(clock = { 1L }, maxRecent = 3)
            val before = t.finishedCounter
            repeat(5) { t.end(id = t.begin(label = "c$it"), ok = true) }
            t.recent shouldHaveSize 3
            t.recent.first().label shouldBe "c4"
            t.finishedCounter shouldNotBe before
        }

        test("reset forgets calls and failure") {
            val t = McpActivityTracker(clock = { 1L })
            t.end(id = t.begin(label = "x"), ok = false)
            t.reset()
            t.phase shouldBe McpActivityPhase.Ready
            t.recent shouldHaveSize 0
        }

        context("server integration") {
            val token = "A".repeat(43)
            val tool =
                object : McpTool {
                    override val descriptor =
                        McpToolDescriptor(name = "echo", description = "echo", inputSchema = buildJsonObject { put("type", "object") })

                    override fun call(arguments: JsonObject): List<McpContent> = listOf(McpContent(type = "text", text = "pong"))
                }
            val handler =
                McpRequestHandler(
                    registry = McpToolRegistry(tools = listOf(tool)),
                    serverInfo = McpServerInfo(name = "t", version = "1"),
                    protocolVersion = "2025-03-26",
                )
            val activity = McpActivityTracker()
            val server = DesktopMcpServer(handler = handler, tokenProvider = { token }, activity = activity)
            val port = server.start(preferredPort = 0).port
            val headers = listOf("Host" to "127.0.0.1:$port", "Content-Type" to "application/json")

            afterSpec { server.stop() }

            test("authenticated tools/call is recorded with its tool name") {
                val body = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{}}}"""
                RawHttp
                    .send(
                        port = port,
                        headers = headers + ("Authorization" to "Bearer $token"),
                        body = body.toByteArray(),
                    ).status shouldBe
                    200
                activity.recent.first().label shouldBe "echo"
                activity.recent.first().ok shouldBe true
                activity.phase shouldBe McpActivityPhase.Ready
            }

            test("unauthenticated requests never touch the indicator") {
                val before = activity.finishedCounter
                val body = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{}}}"""
                RawHttp
                    .send(
                        port = port,
                        headers = headers + ("Authorization" to "Bearer ${"B".repeat(43)}"),
                        body = body.toByteArray(),
                    ).status shouldBe
                    401
                activity.finishedCounter shouldBe before
            }

            test("a JSON-RPC error marks the call as failed") {
                val body = """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"does-not-exist","arguments":{}}}"""
                RawHttp.send(port = port, headers = headers + ("Authorization" to "Bearer $token"), body = body.toByteArray())
                activity.recent.first().ok shouldBe false
                activity.phase shouldBe McpActivityPhase.Failed
            }
        }
    })
