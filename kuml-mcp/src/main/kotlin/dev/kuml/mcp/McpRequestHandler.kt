package dev.kuml.mcp

import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.resources.McpResourceException
import dev.kuml.mcp.resources.ResourceRegistry
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.mcp.tools.McpToolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Name and version reported to clients in the `initialize` response. */
public data class McpServerInfo(
    public val name: String,
    public val version: String,
)

/**
 * Transport-independent JSON-RPC 2.0 request handler for an MCP server.
 *
 * Supported methods: `initialize`, `notifications/initialized` / `initialized` (no response),
 * `ping`, `tools/list`, `tools/call` and — only for the stdio server — `resources/list` /
 * `resources/read`.
 */
public class McpRequestHandler internal constructor(
    private val registry: McpToolRegistry,
    private val serverInfo: McpServerInfo,
    private val protocolVersion: String,
    private val includeResources: Boolean,
) {
    /** Creates a handler that serves the tools of [registry] (and no resources). */
    public constructor(
        registry: McpToolRegistry,
        serverInfo: McpServerInfo,
        protocolVersion: String = DEFAULT_PROTOCOL_VERSION,
    ) : this(registry, serverInfo, protocolVersion, false)

    /** Handles one JSON-RPC message; returns the encoded response, or `null` when none is due. */
    public fun handle(requestJson: String): String? = handleRequest(requestJson)?.let { json.encodeToString(it) }

    internal fun handleRequest(line: String): JsonRpcResponse? {
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
            "resources/list" -> if (includeResources) handleResourcesList(request) else methodNotFound(request)
            "resources/read" -> if (includeResources) handleResourcesRead(request) else methodNotFound(request)
            else -> methodNotFound(request)
        }
    }

    private fun methodNotFound(request: JsonRpcRequest): JsonRpcResponse =
        JsonRpcResponse(
            id = request.id,
            error = JsonRpcError(code = -32601, message = "Method not found: ${request.method}"),
        )

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result =
            buildJsonObject {
                put("protocolVersion", protocolVersion)
                putJsonObject("serverInfo") {
                    put("name", serverInfo.name)
                    put("version", serverInfo.version)
                }
                putJsonObject("capabilities") {
                    putJsonObject("tools") {}
                    if (includeResources) putJsonObject("resources") {}
                }
            }
        return JsonRpcResponse(id = request.id, result = result)
    }

    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val toolsArray =
            buildJsonObject {
                putJsonArray("tools") {
                    registry.descriptors.forEach { desc ->
                        add(json.encodeToJsonElement(McpToolDescriptor.serializer(), desc))
                    }
                }
            }
        return JsonRpcResponse(id = request.id, result = toolsArray)
    }

    private fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val params =
            request.params as? JsonObject
                ?: return errorResponse(request = request, code = -32602, message = "Missing params")

        val toolName =
            (params["name"] as? JsonPrimitive)?.content
                ?: return errorResponse(request = request, code = -32602, message = "Missing params.name")

        val rawArguments = params["arguments"]
        if (rawArguments != null && rawArguments !is JsonObject && rawArguments !is JsonNull) {
            return errorResponse(request = request, code = -32602, message = "Invalid params.arguments")
        }
        val arguments = rawArguments as? JsonObject ?: JsonObject(emptyMap())

        return try {
            val contents = registry.dispatch(name = toolName, arguments = arguments)
            val result = json.encodeToJsonElement(McpToolResult.serializer(), McpToolResult(content = contents, isError = false))
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: McpToolException) {
            toolError(request = request, text = e.message ?: "Tool error")
        } catch (e: ScriptEvaluationException) {
            toolError(request = request, text = "Script error: ${e.message}")
        } catch (e: Exception) {
            toolError(request = request, text = "Internal error: ${e.message}")
        }
    }

    private fun toolError(
        request: JsonRpcRequest,
        text: String,
    ): JsonRpcResponse {
        val errResult =
            json.encodeToJsonElement(
                McpToolResult.serializer(),
                McpToolResult(content = listOf(McpContent(type = "text", text = text)), isError = true),
            )
        return JsonRpcResponse(id = request.id, result = errResult)
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
            request.params as? JsonObject
                ?: return errorResponse(request = request, code = -32602, message = "Missing params")

        val uri =
            (params["uri"] as? JsonPrimitive)?.content
                ?: return errorResponse(request = request, code = -32602, message = "Missing params.uri")

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
            errorResponse(request = request, code = -32602, message = e.message ?: "Unknown resource: '$uri'")
        }
    }

    private fun errorResponse(
        request: JsonRpcRequest,
        code: Int,
        message: String,
    ): JsonRpcResponse = JsonRpcResponse(id = request.id, error = JsonRpcError(code = code, message = message))

    public companion object {
        /** Protocol version spoken by the stdio server. */
        public const val DEFAULT_PROTOCOL_VERSION: String = "2024-11-05"

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
    }
}
