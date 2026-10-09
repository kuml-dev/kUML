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

/**
 * Registry of the stdio server's tools.
 *
 * NOTE: `kuml-ai-tools`' `InProcessMcpTransport` reaches this object by reflection
 * (`INSTANCE`, `dispatch*`, `getDescriptors*` — all declared directly on this class).
 * Keep it an `object` with self-declared (not inherited) members that merely delegate.
 */
internal object ToolRegistry {
    /** Shared session manager — singleton, lives for the lifetime of the MCP server process. */
    private val sessionManager = RuntimeSessionManager()

    internal val registry: McpToolRegistry =
        McpToolRegistry(
            tools =
                listOf(
                    RenderTool,
                    ValidateTool,
                    ListElementsTool,
                    DescribeTool,
                    GenerateTool,
                    // V3.3.1 — curated per-type DSL examples
                    ExamplesTool,
                    // V2.0.27 — Behaviour-Runtime tools
                    RunStartTool(sessionManager),
                    RunEventTool(sessionManager),
                    RunSnapshotTool(sessionManager),
                    RunPatchTool(sessionManager),
                    RunStopTool(sessionManager),
                ),
        )

    internal val tools: List<McpTool> get() = registry.tools

    internal val descriptors: List<McpToolDescriptor> get() = registry.descriptors

    /**
     * Dispatches a tool call by name.
     * @throws McpToolException if tool is unknown or call fails
     */
    internal fun dispatch(
        name: String,
        arguments: JsonObject,
    ): List<McpContent> = registry.dispatch(name = name, arguments = arguments)
}
