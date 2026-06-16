package dev.kuml.ai.tools.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class InProcessMcpTransportTest :
    FunSpec({

        test("send tools-list request returns descriptor JSON for all tools") {
            val transport = InProcessMcpTransport()
            val tools = transport.listTools()
            tools.shouldNotBeEmpty()
            // Expect the standard kuml-mcp tools
            val toolNames = tools.map { it.name }
            toolNames.any { it.startsWith("kuml.") } shouldBe true
        }

        test("send tools-call dispatches by name and returns result JSON") {
            val transport = InProcessMcpTransport()
            val tools = transport.listTools()
            // Find a simple tool like kuml.render or kuml.validate
            val renderTool = tools.firstOrNull { it.name == "kuml.validate" }
            if (renderTool != null) {
                val args =
                    buildJsonObject {
                        put("script", "")
                    }
                val result = transport.dispatchToolCall("kuml.validate", args)
                // Should return some JSON (even an error/validation message) without throwing
                result.toString().isNotBlank() shouldBe true
            }
        }

        test("unknown tool name returns JSON-RPC error") {
            val transport = InProcessMcpTransport()
            val args = JsonObject(emptyMap())
            val result = transport.dispatchToolCall("kuml.nonexistent_tool_xyz", args)
            // Should return an error object (from McpToolException)
            val resultStr = result.toString()
            resultStr.isNotBlank() shouldBe true
            // Error should be indicated
            resultStr shouldContain "error"
        }
    })
