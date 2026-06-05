package dev.kuml.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement = JsonNull,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
internal data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement = JsonNull,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
internal data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/** A content block returned by a tool. */
@Serializable
internal data class McpContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    @SerialName("mimeType")
    val mimeType: String? = null,
)

/** Standard MCP tool result payload. */
@Serializable
internal data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false,
)

/** A single tool descriptor returned by tools/list. */
@Serializable
internal data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)
