package dev.kuml.mcp

import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.resources.McpResourceException
import dev.kuml.mcp.resources.ResourceRegistry
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.mcp.tools.ToolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * kUML MCP server — JSON-RPC 2.0 over stdio.
 *
 * Protocol version: 2024-11-05
 * Transport: stdio (newline-delimited JSON messages)
 *
 * Supported methods:
 * - initialize
 * - notifications/initialized  (no response)
 * - ping
 * - tools/list
 * - tools/call
 * - resources/list
 * - resources/read
 */
internal object McpServer {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Run the server, reading from stdin and writing to stdout until EOF. */
    internal fun run() {
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        val writer = System.out.bufferedWriter(Charsets.UTF_8)

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val raw = line ?: continue
            if (raw.isBlank()) continue

            val response = handleLine(raw) ?: continue
            writer.write(json.encodeToString(response))
            writer.newLine()
            writer.flush()
        }
    }

    internal fun handleLine(line: String): JsonRpcResponse? {
        val request =
            try {
                json.decodeFromString<JsonRpcRequest>(line)
            } catch (e: Exception) {
                return JsonRpcResponse(
                    id = JsonNull,
                    error = JsonRpcError(code = -32700, message = "Parse error: ${e.message}"),
                )
            }

        return when (request.method) {
            "initialize" -> handleInitialize(request)
            "notifications/initialized" -> null
            "initialized" -> null
            "ping" -> JsonRpcResponse(id = request.id, result = buildJsonObject {})
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolsCall(request)
            "resources/list" -> handleResourcesList(request)
            "resources/read" -> handleResourcesRead(request)
            else ->
                JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(code = -32601, message = "Method not found: ${request.method}"),
                )
        }
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result =
            buildJsonObject {
                put("protocolVersion", "2024-11-05")
                putJsonObject("serverInfo") {
                    put("name", "kuml-mcp")
                    put("version", "1.0.0")
                }
                putJsonObject("capabilities") {
                    putJsonObject("tools") {}
                    putJsonObject("resources") {}
                }
            }
        return JsonRpcResponse(id = request.id, result = result)
    }

    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val toolsArray =
            buildJsonObject {
                putJsonArray("tools") {
                    ToolRegistry.descriptors.forEach { desc ->
                        add(json.encodeToJsonElement(McpToolDescriptor.serializer(), desc))
                    }
                }
            }
        return JsonRpcResponse(id = request.id, result = toolsArray)
    }

    private fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val params =
            request.params?.jsonObject
                ?: return errorResponse(request, -32602, "Missing params")

        val toolName =
            params["name"]?.jsonPrimitive?.content
                ?: return errorResponse(request, -32602, "Missing params.name")

        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        return try {
            val contents = ToolRegistry.dispatch(toolName, arguments)
            val result = json.encodeToJsonElement(McpToolResult.serializer(), McpToolResult(content = contents, isError = false))
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: McpToolException) {
            val errResult =
                json.encodeToJsonElement(
                    McpToolResult.serializer(),
                    McpToolResult(
                        content = listOf(McpContent(type = "text", text = e.message ?: "Tool error")),
                        isError = true,
                    ),
                )
            JsonRpcResponse(id = request.id, result = errResult)
        } catch (e: ScriptEvaluationException) {
            val errResult =
                json.encodeToJsonElement(
                    McpToolResult.serializer(),
                    McpToolResult(
                        content = listOf(McpContent(type = "text", text = "Script error: ${e.message}")),
                        isError = true,
                    ),
                )
            JsonRpcResponse(id = request.id, result = errResult)
        } catch (e: Exception) {
            val errResult =
                json.encodeToJsonElement(
                    McpToolResult.serializer(),
                    McpToolResult(
                        content = listOf(McpContent(type = "text", text = "Internal error: ${e.message}")),
                        isError = true,
                    ),
                )
            JsonRpcResponse(id = request.id, result = errResult)
        }
    }

    private fun handleResourcesList(request: JsonRpcRequest): JsonRpcResponse {
        val resourcesArray =
            buildJsonObject {
                putJsonArray("resources") {
                    ResourceRegistry.descriptors.forEach { desc ->
                        add(json.encodeToJsonElement(McpResourceDescriptor.serializer(), desc))
                    }
                }
            }
        return JsonRpcResponse(id = request.id, result = resourcesArray)
    }

    private fun handleResourcesRead(request: JsonRpcRequest): JsonRpcResponse {
        val params =
            request.params?.jsonObject
                ?: return errorResponse(request, -32602, "Missing params")

        val uri =
            params["uri"]?.jsonPrimitive?.content
                ?: return errorResponse(request, -32602, "Missing params.uri")

        return try {
            val contents = ResourceRegistry.read(uri)
            val result =
                buildJsonObject {
                    putJsonArray("contents") {
                        add(json.encodeToJsonElement(McpResourceContents.serializer(), contents))
                    }
                }
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: McpResourceException) {
            errorResponse(request, -32602, e.message ?: "Unknown resource: '$uri'")
        }
    }

    private fun errorResponse(
        request: JsonRpcRequest,
        code: Int,
        message: String,
    ): JsonRpcResponse = JsonRpcResponse(id = request.id, error = JsonRpcError(code = code, message = message))
}
