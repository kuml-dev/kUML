package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.JsonObject

/**
 * An immutable set of [McpTool]s addressable by name.
 *
 * Tool names must be distinct.
 */
public class McpToolRegistry(
    tools: List<McpTool>,
) {
    public val tools: List<McpTool> = tools.toList()

    init {
        val names = this.tools.map { it.descriptor.name }
        require(names.size == names.toSet().size) {
            "Duplicate tool names: ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
    }

    public val descriptors: List<McpToolDescriptor> get() = tools.map { it.descriptor }

    /**
     * Dispatches a tool call by name.
     * @throws McpToolException if the tool is unknown or the call fails
     */
    @Throws(McpToolException::class)
    public fun dispatch(
        name: String,
        arguments: JsonObject,
    ): List<McpContent> {
        val tool =
            tools.firstOrNull { it.descriptor.name == name }
                ?: throw McpToolException(message = "Unknown tool: '$name'")
        return tool.call(arguments)
    }
}
