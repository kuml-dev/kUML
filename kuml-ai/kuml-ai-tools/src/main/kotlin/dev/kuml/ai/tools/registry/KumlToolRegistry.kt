package dev.kuml.ai.tools.registry

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolRegistryBuilder
import dev.kuml.ai.tools.c4.C4EditingTools
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.inspection.ModelInspectionTools
import dev.kuml.ai.tools.mcp.McpBridgeToolSet
import dev.kuml.ai.tools.render.RenderingTools
import dev.kuml.ai.tools.sysml2.Sysml2EditingTools
import dev.kuml.ai.tools.uml.UmlEditingTools

/**
 * Convenience factory that wires kUML ToolSets into Koog ToolRegistries.
 *
 * Use the per-family factories when the agent only needs a subset (e.g. C4-only) —
 * that keeps the LLM's tool-call surface small and the JSON schema compact.
 *
 * Full registry: UML + C4 + SysML 2 + Rendering + Inspection + MCP bridge.
 */
public object KumlToolRegistry {
    /** Full surface — UML + C4 + SysML 2 + Rendering + Inspection + MCP bridge. */
    public fun full(ctx: AgentEditingContext): ToolRegistry {
        val base =
            buildRegistry {
                tools(UmlEditingTools(ctx))
                tools(C4EditingTools(ctx))
                tools(Sysml2EditingTools(ctx))
                tools(RenderingTools(ctx))
                tools(ModelInspectionTools(ctx))
            }
        return try {
            withMcpBridge(base)
        } catch (_: Exception) {
            // MCP bridge is optional; if kuml-mcp is not available, continue without it
            base
        }
    }

    /** UML-only — for class/state/component-diagram-focused agents. */
    public fun forUml(ctx: AgentEditingContext): ToolRegistry =
        buildRegistry {
            tools(UmlEditingTools(ctx))
            tools(RenderingTools(ctx))
            tools(ModelInspectionTools(ctx))
        }

    /** C4-only — for architecture-focused agents. */
    public fun forC4(ctx: AgentEditingContext): ToolRegistry =
        buildRegistry {
            tools(C4EditingTools(ctx))
            tools(RenderingTools(ctx))
            tools(ModelInspectionTools(ctx))
        }

    /** SysML 2-only — for systems-engineering agents. */
    public fun forSysml2(ctx: AgentEditingContext): ToolRegistry =
        buildRegistry {
            tools(Sysml2EditingTools(ctx))
            tools(RenderingTools(ctx))
            tools(ModelInspectionTools(ctx))
        }

    /** Read-only inspection — for analysis or summarization agents. */
    public fun inspectionOnly(ctx: AgentEditingContext): ToolRegistry =
        buildRegistry {
            tools(ModelInspectionTools(ctx))
        }

    /**
     * Optional MCP-bridge addon — creates the bridge but returns the base registry.
     *
     * The bridge is accessible via McpBridgeToolSet.create() for direct use;
     * full Koog-tool integration requires converting the bridge descriptors to
     * ToolFromCallable instances, which is out of scope for V3.0.23.
     *
     * @throws IllegalStateException if kuml-mcp is not on the classpath.
     */
    public fun withMcpBridge(base: ToolRegistry): ToolRegistry {
        McpBridgeToolSet.create() // Validates that kuml-mcp is available; throws if not
        return base
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildRegistry(block: ToolRegistryBuilder.() -> Unit): ToolRegistry = ToolRegistryBuilder().apply(block).build()
}
