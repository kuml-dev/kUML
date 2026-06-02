package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.JsonObject

internal interface McpTool {
    val descriptor: McpToolDescriptor

    /**
     * Execute the tool.
     * @throws dev.kuml.core.script.ScriptEvaluationException on script error
     * @throws java.io.IOException on I/O error
     * @throws IllegalArgumentException on bad arguments
     */
    fun call(arguments: JsonObject): List<McpContent>
}
