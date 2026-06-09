package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.runtime.RuntimeSessionManager
import dev.kuml.mcp.runtime.tools.RunEventTool
import dev.kuml.mcp.runtime.tools.RunPatchTool
import dev.kuml.mcp.runtime.tools.RunSnapshotTool
import dev.kuml.mcp.runtime.tools.RunStartTool
import dev.kuml.mcp.runtime.tools.RunStopTool
import kotlinx.serialization.json.JsonObject

internal object ToolRegistry {
    /** Shared session manager — singleton, lives for the lifetime of the MCP server process. */
    private val sessionManager = RuntimeSessionManager()

    internal val tools: List<McpTool> =
        listOf(
            RenderTool,
            ValidateTool,
            ListElementsTool,
            DescribeTool,
            GenerateTool,
            // V2.0.27 — Behaviour-Runtime tools
            RunStartTool(sessionManager),
            RunEventTool(sessionManager),
            RunSnapshotTool(sessionManager),
            RunPatchTool(sessionManager),
            RunStopTool(sessionManager),
        )

    internal val descriptors: List<McpToolDescriptor> get() = tools.map { it.descriptor }

    /**
     * Dispatches a tool call by name.
     * @throws McpToolException if tool is unknown or call fails
     */
    internal fun dispatch(
        name: String,
        arguments: JsonObject,
    ): List<McpContent> {
        val tool =
            tools.firstOrNull { it.descriptor.name == name }
                ?: throw McpToolException("Unknown tool: '$name'")
        return tool.call(arguments)
    }
}

internal class McpToolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
