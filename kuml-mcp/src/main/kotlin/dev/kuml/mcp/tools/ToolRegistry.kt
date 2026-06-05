package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.JsonObject

internal object ToolRegistry {
    internal val tools: List<McpTool> =
        listOf(
            RenderTool,
            ValidateTool,
            ListElementsTool,
            DescribeTool,
            GenerateTool,
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
