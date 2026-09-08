package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.sandbox.SandboxPolicy

/** Shared test helpers — hand-built [TokenFlowSpec]s, independent of any metamodel/DSL. */
internal object TestFixtures {
    fun action(
        id: String,
        body: String? = id,
    ) = TokenFlowNode(id = id, kind = TokenNodeKind.ACTION, actionBody = body)

    fun initial(id: String = "init") = TokenFlowNode(id = id, kind = TokenNodeKind.INITIAL)

    fun terminateFinal(id: String = "fin") = TokenFlowNode(id = id, kind = TokenNodeKind.TERMINATE_FINAL)

    fun flowFinal(id: String) = TokenFlowNode(id = id, kind = TokenNodeKind.FLOW_FINAL)

    fun endEvent(id: String) = TokenFlowNode(id = id, kind = TokenNodeKind.END_EVENT)

    fun gateway(
        id: String,
        family: GatewayFamily,
        converging: Boolean,
        diverging: Boolean,
        defaultEdgeId: String? = null,
    ) = TokenFlowNode(
        id = id,
        kind = TokenNodeKind.GATEWAY,
        gateway = GatewaySpec(family = family, converging = converging, diverging = diverging, defaultEdgeId = defaultEdgeId),
    )

    fun edge(
        id: String,
        from: String,
        to: String,
        guard: String? = null,
        isDefault: Boolean = false,
    ) = TokenFlowEdge(id = id, sourceNodeId = from, targetNodeId = to, guard = guard, isDefault = isDefault)

    fun spec(
        nodes: List<TokenFlowNode>,
        edges: List<TokenFlowEdge>,
        id: String = "spec",
        name: String = "spec",
    ) = TokenFlowSpec.of(id = id, name = name, nodes = nodes, edges = edges)

    /** Fast test policy: short guard timeout so timeout tests don't slow the suite. */
    fun engineOf(
        spec: TokenFlowSpec,
        limits: TokenFlowLimits = TokenFlowLimits(),
        options: TokenFlowOptions = TokenFlowOptions(),
    ) = TokenFlowEngine.sandboxed(spec = spec, policy = SandboxPolicy(guardTimeoutMs = 2_000L), limits = limits, options = options)
}
