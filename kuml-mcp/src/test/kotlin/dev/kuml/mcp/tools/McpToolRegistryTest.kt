package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class McpToolRegistryTest :
    FunSpec({
        fun tool(toolName: String): McpTool =
            object : McpTool {
                override val descriptor =
                    McpToolDescriptor(name = toolName, description = "d", inputSchema = buildJsonObject {})

                override fun call(arguments: JsonObject): List<McpContent> = listOf(McpContent(type = "text", text = toolName))
            }

        test("duplicate tool names are rejected") {
            shouldThrow<IllegalArgumentException> { McpToolRegistry(tools = listOf(tool("a"), tool("a"))) }
        }

        test("unknown tool raises McpToolException") {
            val registry = McpToolRegistry(tools = listOf(tool("a")))
            shouldThrow<McpToolException> { registry.dispatch(name = "zzz", arguments = JsonObject(emptyMap())) }
        }

        test("known tool is dispatched") {
            val registry = McpToolRegistry(tools = listOf(tool("a"), tool("b")))
            registry.dispatch(name = "b", arguments = JsonObject(emptyMap()))[0].text shouldBe "b"
        }
    })
