package dev.kuml.ai.spi

/**
 * Coarse-grained capability a [KumlToolSetFactory] declares it needs at runtime.
 *
 * Surfaced to the user (e.g. `kuml ai tools list`) so they can see, before any
 * tool runs, whether an external tool set wants file-system, network, or shell
 * access. This is an *advisory* declaration — V3.1.16 does not yet sandbox or
 * enforce these. Enforcement (capability-gated execution) is deferred to V3.2+.
 */
public enum class ToolSetCapability {
    /** Reads or writes files on the host filesystem. */
    FILE_SYSTEM,

    /** Makes outbound network calls (HTTP, sockets, …). */
    NETWORK,

    /** Spawns subprocesses / runs shell commands. */
    SHELL,
}

/**
 * Service Provider Interface for third-party kUML agent tool sets.
 *
 * A third-party module implements this interface and registers its FQCN in
 * `META-INF/services/dev.kuml.ai.spi.KumlToolSetFactory`. kUML discovers it via
 * [java.util.ServiceLoader] (see `KumlToolRegistry.discoverExternal`) and merges
 * the produced tool set into the Koog ToolRegistry handed to the agent.
 *
 * **Why [create] takes and returns [Any]:** this artifact has *zero* Koog and zero
 * kuml-ai-tools dependency, so third parties can implement the SPI with a single
 * lightweight dependency. The `context` argument is a kUML `AgentEditingContext`
 * (defined in kuml-ai-tools) typed as [Any]; the return value must be a Koog
 * `ToolSet`. kuml-ai-tools casts both at the registry boundary. A ClassCastException
 * there means the implementor returned the wrong type or mis-cast the context.
 *
 * **Collision rule (mirrors V3.1.15 providers):** if an external factory's [id]
 * collides with a built-in tool-set id (`uml`, `c4`, `sysml2`, `render`,
 * `inspection`, `mcp`) the built-in wins and the external factory is skipped with a
 * warning log. External-vs-external id collisions: first-registered wins, the later
 * one is skipped with a warning.
 */
public interface KumlToolSetFactory {
    /** Stable lowercase id, unique across all tool sets. Example: "jira-tools". */
    public val id: String

    /** Human-readable name for CLI tables and UI. */
    public val displayName: String

    /**
     * Capabilities this tool set needs. Advisory in V3.1.16 — shown to the user,
     * not enforced. Return an empty set for a pure in-memory model-editing tool set.
     */
    public val requiredCapabilities: Set<ToolSetCapability>

    /**
     * Build the Koog `ToolSet` for an agent session.
     *
     * @param context the kUML `AgentEditingContext` for this session, typed as [Any]
     *   to keep the SPI Koog/tools-free. Cast it to
     *   `dev.kuml.ai.tools.context.AgentEditingContext` inside the implementation
     *   (kuml-ai-tools is on the runtime classpath of any real deployment).
     * @return a Koog `ai.koog.agents.core.tools.reflect.ToolSet` instance, typed as [Any].
     */
    public fun create(context: Any): Any
}
