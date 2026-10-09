package dev.kuml.mcp

import dev.kuml.mcp.tools.McpTool
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.mcp.tools.McpToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpRequestHandlerTest :
    FunSpec({
        fun fakeTool(
            toolName: String,
            body: (JsonObject) -> List<McpContent>,
        ): McpTool =
            object : McpTool {
                override val descriptor =
                    McpToolDescriptor(
                        name = toolName,
                        description = "fake",
                        inputSchema = buildJsonObject { put("type", "object") },
                    )

                override fun call(arguments: JsonObject): List<McpContent> = body(arguments)
            }

        val echo = fakeTool("echo") { listOf(McpContent(type = "text", text = "hi")) }
        val boom = fakeTool("boom") { throw McpToolException(message = "nope") }
        val handler =
            McpRequestHandler(
                registry = McpToolRegistry(tools = listOf(echo, boom)),
                serverInfo = McpServerInfo(name = "test-server", version = "9.9"),
                protocolVersion = "2025-03-26",
            )

        fun call(json: String): JsonObject = Json.parseToJsonElement(handler.handle(json)!!).jsonObject

        test("initialize reports the given protocol version and server info, without resources") {
            val result = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")["result"]!!.jsonObject
            result["protocolVersion"]!!.jsonPrimitive.content shouldBe "2025-03-26"
            result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "test-server"
            result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content shouldBe "9.9"
            (result["capabilities"]!!.jsonObject.containsKey("resources")) shouldBe false
            (result["capabilities"]!!.jsonObject.containsKey("tools")) shouldBe true
        }

        test("tools/list returns exactly the registry tools") {
            val tools = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!.jsonObject["tools"]!!.jsonArray
            tools.map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldBe listOf("echo", "boom")
        }

        test("tools/call returns the tool result") {
            val result =
                call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{}}}""")["result"]!!.jsonObject
            result["isError"]!!.jsonPrimitive.content shouldBe "false"
            result["content"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content shouldBe "hi"
        }

        test("tools/call maps McpToolException to isError = true with the verbatim message") {
            val result =
                call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boom"}}""")["result"]!!.jsonObject
            result["isError"]!!.jsonPrimitive.content shouldBe "true"
            result["content"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content shouldBe "nope"
        }

        test("notifications/initialized yields no response") {
            handler.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") shouldBe null
        }

        test("unknown method yields -32601, also for resources when disabled") {
            call("""{"jsonrpc":"2.0","id":1,"method":"nope"}""")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "-32601"
            call("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "-32601"
        }

        test("invalid JSON yields -32700") {
            call("{not json")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "-32700"
        }

        test("ping returns an empty result") {
            handler.handle("""{"jsonrpc":"2.0","id":3,"method":"ping"}""")!! shouldContain "\"result\":{}"
        }

        test("descriptor annotations are serialised only when present") {
            val annotated =
                McpToolDescriptor(
                    name = "a",
                    description = "d",
                    inputSchema = buildJsonObject {},
                    annotations = McpToolAnnotations(readOnlyHint = true),
                )
            val h =
                McpRequestHandler(
                    registry =
                        McpToolRegistry(
                            tools =
                                listOf(
                                    object : McpTool {
                                        override val descriptor = annotated

                                        override fun call(arguments: JsonObject): List<McpContent> = emptyList()
                                    },
                                    echo,
                                ),
                        ),
                    serverInfo = McpServerInfo(name = "x", version = "1"),
                )
            val raw = h.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!
            raw shouldContain "\"readOnlyHint\":true"
            val tools =
                Json
                    .parseToJsonElement(raw)
                    .jsonObject["result"]!!
                    .jsonObject["tools"]!!
                    .jsonArray
            tools[1].jsonObject.containsKey("annotations") shouldBe false
            raw shouldNotContain "\"title\""
        }
    })
