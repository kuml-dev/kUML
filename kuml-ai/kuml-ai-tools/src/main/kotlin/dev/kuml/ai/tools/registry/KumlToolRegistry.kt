package dev.kuml.ai.tools.registry

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.spi.KumlToolSetFactory
import dev.kuml.ai.tools.c4.C4EditingTools
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.inspection.ModelInspectionTools
import dev.kuml.ai.tools.mcp.McpBridgeToolSet
import dev.kuml.ai.tools.render.RenderingTools
import dev.kuml.ai.tools.sysml2.Sysml2EditingTools
import dev.kuml.ai.tools.uml.UmlEditingTools
import org.slf4j.LoggerFactory
import java.util.ServiceLoader

/**
 * Convenience factory that wires kUML ToolSets into Koog ToolRegistries.
 *
 * Use the per-family factories when the agent only needs a subset (e.g. C4-only) —
 * that keeps the LLM's tool-call surface small and the JSON schema compact.
 *
 * Full registry: UML + C4 + SysML 2 + Rendering + Inspection + MCP bridge.
 *
 * V3.1.16 adds ServiceLoader-based external tool set discovery via [discoverExternal]
 * and a combined built-in + external registry via [buildWithExternal].
 */
public object KumlToolRegistry {
    private val log = LoggerFactory.getLogger(KumlToolRegistry::class.java)

    /** Ids reserved by built-in tool sets — external factories may not reuse these. */
    private val BUILT_IN_IDS: Set<String> =
        setOf("uml", "c4", "sysml2", "render", "inspection", "mcp")

    @Volatile private var cachedExternal: List<KumlToolSetFactory>? = null

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

    // ── External tool set discovery (V3.1.16) ─────────────────────────────────

    /**
     * Discover external [KumlToolSetFactory] implementations via ServiceLoader.
     *
     * Result is cached thread-safely on first call. Factories whose [KumlToolSetFactory.id]
     * collides with a built-in id (or with an earlier external factory) are dropped with a
     * warning. Use [discoverExternalFrom] in tests to inject stubs without a real
     * ServiceLoader; use [resetExternalCacheForTest] to clear the cache.
     */
    public fun discoverExternal(): List<KumlToolSetFactory> =
        cachedExternal ?: synchronized(this) {
            cachedExternal ?: discoverExternalFrom(
                ServiceLoader.load(KumlToolSetFactory::class.java),
            ).also { cachedExternal = it }
        }

    /**
     * Testable seam: filter an explicit iterable of factories. NOT cached.
     *
     * Factories with ids that collide with built-in ids or earlier entries in the
     * iterable are dropped with a warning log.
     */
    internal fun discoverExternalFrom(factories: Iterable<KumlToolSetFactory>): List<KumlToolSetFactory> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<KumlToolSetFactory>()
        for (f in factories) {
            when {
                f.id in BUILT_IN_IDS ->
                    log.warn(
                        "External tool set '{}' ignored — collides with a built-in tool-set id. " +
                            "Choose a unique id.",
                        f.id,
                    )
                f.id in seen ->
                    log.warn(
                        "External tool set '{}' ignored — another external factory already " +
                            "registered this id.",
                        f.id,
                    )
                else -> {
                    seen += f.id
                    result += f
                }
            }
        }
        return result
    }

    /** Test-only: reset the discoverExternal() cache so each test gets a fresh result. */
    internal fun resetExternalCacheForTest() {
        synchronized(this) { cachedExternal = null }
    }

    /**
     * Full built-in surface PLUS every discovered external tool set, merged into one
     * Koog ToolRegistry.
     *
     * External factories are instantiated with [ctx] and each one that throws during
     * [KumlToolSetFactory.create] (or returns a non-ToolSet) is skipped with a warning —
     * one bad plugin must not break the whole registry.
     *
     * Delegates discovery to [discoverExternal]. Use [buildWithExternalFrom] in tests to
     * supply an explicit factory list without a real ServiceLoader.
     */
    public fun buildWithExternal(ctx: AgentEditingContext): ToolRegistry = buildWithExternalFrom(ctx, discoverExternal())

    /**
     * Testable seam for [buildWithExternal]: accepts an explicit factory list instead of
     * calling [discoverExternal]. NOT cached.
     */
    internal fun buildWithExternalFrom(
        ctx: AgentEditingContext,
        external: List<KumlToolSetFactory>,
    ): ToolRegistry {
        val builtIn = full(ctx)
        if (external.isEmpty()) return builtIn

        val externalRegistries =
            external.mapNotNull { factory ->
                runCatching {
                    val produced = factory.create(ctx)
                    val toolSet =
                        produced as? ToolSet
                            ?: error(
                                "create() returned ${produced::class.qualifiedName}, not a Koog ToolSet",
                            )
                    buildRegistry { tools(toolSet) }
                }.onFailure { e ->
                    log.warn("External tool set '{}' skipped: {}", factory.id, e.message)
                }.getOrNull()
            }

        return externalRegistries.fold(builtIn) { acc, reg -> acc + reg }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildRegistry(block: ToolRegistryBuilder.() -> Unit): ToolRegistry = ToolRegistryBuilder().apply(block).build()
}
