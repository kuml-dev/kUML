package dev.kuml.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A JSON-RPC 2.0 request (or notification, when no `id` is present). */
@Serializable
public data class JsonRpcRequest(
    public val jsonrpc: String = "2.0",
    public val id: JsonElement = JsonNull,
    public val method: String,
    public val params: JsonElement? = null,
)

/** A JSON-RPC 2.0 response carrying either [result] or [error]. */
@Serializable
public data class JsonRpcResponse(
    public val jsonrpc: String = "2.0",
    public val id: JsonElement = JsonNull,
    public val result: JsonElement? = null,
    public val error: JsonRpcError? = null,
)

/** A JSON-RPC 2.0 error object. */
@Serializable
public data class JsonRpcError(
    public val code: Int,
    public val message: String,
    public val data: JsonElement? = null,
)

/** A content block returned by a tool. */
@Serializable
public data class McpContent(
    public val type: String,
    public val text: String? = null,
    public val data: String? = null,
    @SerialName("mimeType")
    public val mimeType: String? = null,
)

/** Standard MCP tool result payload. */
@Serializable
public data class McpToolResult(
    public val content: List<McpContent>,
    public val isError: Boolean = false,
)

/**
 * Optional behavioural hints for a tool (MCP spec 2025-03-26, `ToolAnnotations`).
 *
 * `null` fields are omitted from the wire format.
 */
@Serializable
public data class McpToolAnnotations(
    public val title: String? = null,
    public val readOnlyHint: Boolean? = null,
    public val destructiveHint: Boolean? = null,
    public val idempotentHint: Boolean? = null,
    public val openWorldHint: Boolean? = null,
)

/** A single tool descriptor returned by tools/list. */
@Serializable
public data class McpToolDescriptor(
    public val name: String,
    public val description: String,
    public val inputSchema: JsonObject,
    public val annotations: McpToolAnnotations? = null,
)

/** A single resource descriptor returned by resources/list. */
@Serializable
internal data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
)

/** A single resource content block returned by resources/read. */
@Serializable
internal data class McpResourceContents(
    val uri: String,
    val mimeType: String,
    val text: String,
)
