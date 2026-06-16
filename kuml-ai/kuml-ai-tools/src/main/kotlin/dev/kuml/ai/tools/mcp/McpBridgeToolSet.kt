package dev.kuml.ai.tools.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bridges the existing kuml-mcp tool surface (kuml.render, kuml.validate,
 * kuml.list_elements, kuml.describe, kuml.generate, all kuml.run.*) into the
 * Koog ToolRegistry — exposing them as Koog tools the AIAgent can call.
 *
 * Bridge mechanism: InProcessMcpTransport routes JSON-RPC requests to kuml-mcp's
 * internal ToolRegistry via reflection, bypassing the internal visibility restriction.
 * This avoids spawning a subprocess (high latency) and avoids making kuml-mcp public
 * (invasive). See InProcessMcpTransport KDoc for the migration path.
 *
 * Note: The MCP tools take a `script: String` parameter and operate on DSL source code,
 * NOT on the in-memory AgentEditingContext model. Use UmlEditingTools / C4EditingTools /
 * Sysml2EditingTools for in-memory mutations. The bridge is for agents that have a
 * .kuml.kts script source and want to render/validate it directly.
 */
public class McpBridgeToolSet private constructor(
    private val transport: InProcessMcpTransport,
) : ToolSet {
    private val toolProxies: List<InProcessMcpTransport.McpToolProxy> by lazy {
        transport.listTools()
    }

    /** All kuml-mcp tool descriptors as Koog ToolDescriptors. */
    public fun bridgedTools(): List<ToolDescriptor> =
        toolProxies.map { proxy ->
            val props = proxy.inputSchema["properties"] as? JsonObject
            val requiredArr = proxy.inputSchema["required"] as? JsonArray
            val requiredNames = requiredArr?.map { (it as? JsonPrimitive)?.content ?: "" }?.toSet() ?: emptySet()

            val allParams =
                props?.entries?.map { (propName, propSchema) ->
                    ToolParameterDescriptor(
                        name = propName,
                        description =
                            (propSchema as? JsonObject)
                                ?.get("description")
                                ?.let { (it as? JsonPrimitive)?.content }
                                ?: propName,
                        type = ToolParameterType.String,
                    )
                } ?: emptyList()

            val requiredParams = allParams.filter { it.name in requiredNames }
            val optionalParams = allParams.filter { it.name !in requiredNames }

            ToolDescriptor(
                name = proxy.name,
                description = proxy.description,
                requiredParameters = requiredParams,
                optionalParameters = optionalParams,
            )
        }

    /**
     * Executes the bridged kuml-mcp tool with the given name and arguments.
     * Returns the raw JSON result as a string.
     */
    public fun executeTool(
        toolName: String,
        arguments: JsonObject,
    ): String {
        val result: JsonElement = transport.dispatchToolCall(toolName, arguments)
        return result.toString()
    }

    public companion object {
        /**
         * Creates the McpBridgeToolSet using an in-process transport — no subprocess started.
         *
         * @throws IllegalStateException if kuml-mcp is not on the classpath.
         */
        public fun create(): McpBridgeToolSet = McpBridgeToolSet(InProcessMcpTransport())
    }
}
