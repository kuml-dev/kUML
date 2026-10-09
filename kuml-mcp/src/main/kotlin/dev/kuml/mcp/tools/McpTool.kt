package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.JsonObject

/** A single MCP tool: a [descriptor] advertised via `tools/list` and a [call] implementation. */
public interface McpTool {
    public val descriptor: McpToolDescriptor

    /**
     * Execute the tool.
     * @throws dev.kuml.core.script.ScriptEvaluationException on script error
     * @throws java.io.IOException on I/O error
     * @throws IllegalArgumentException on bad arguments
     */
    public fun call(arguments: JsonObject): List<McpContent>
}

/**
 * Thrown by a tool to report a failure to the client as an `isError = true` tool result.
 * The [message] is delivered verbatim as the result text.
 */
public open class McpToolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
