package dev.kuml.runtime.tokenflow

/**
 * Model-agnostic node kind for the ADR-0015 token-flow engine.
 *
 * Deliberately coarser than [dev.kuml.sysml2.ActivityNodeKind] /
 * `dev.kuml.uml.UmlActivityNodeKind` / BPMN's `GatewayType` + `EventPosition` +
 * `EventDefinition` combination — a single [TokenFlowSpec] is the shared
 * execution target for UML Activity, BPMN Process, and (via
 * `dev.kuml.runtime.activity.ActivityRuntimeSpec`) SysML 2 ACT, so it only
 * needs to distinguish node shapes that actually differ in firing semantics.
 */
public enum class TokenNodeKind {
    /** UML Initial node / BPMN Start Event. */
    INITIAL,

    /** UML Action / BPMN Task, SubProcess, CallActivity, intermediate Event. */
    ACTION,

    /** All gateway families (exclusive/parallel/inclusive); details in [TokenFlowNode.gateway]. */
    GATEWAY,

    /** UML FlowFinal — consumes only the token that reaches it. */
    FLOW_FINAL,

    /**
     * BPMN End Event (`NONE` or a typed end event other than TERMINATE) —
     * consumes the token that reaches it; the activity as a whole terminates
     * only once every token has been consumed (see the "empty marking"
     * postcondition documented on [TokenFlowEngine]).
     */
    END_EVENT,

    /** UML ActivityFinal / BPMN Terminate End Event — consumes ALL tokens, ends the activity immediately. */
    TERMINATE_FINAL,

    /** UML ObjectNode — pass-through, no [dev.kuml.runtime.TraceEntry.ActivityActionInvoked] emitted. */
    OBJECT_NODE,
}

/** Routing family of a [TokenFlowNode] of kind [TokenNodeKind.GATEWAY]. */
public enum class GatewayFamily {
    /** Exactly one branch is taken (Decision/Merge in UML, `EXCLUSIVE` in BPMN). */
    EXCLUSIVE,

    /** All branches are taken / all must synchronise (Fork/Join in UML, `PARALLEL` in BPMN). */
    PARALLEL,

    /** One-or-more branches are taken / a structural "nothing more can arrive" join (BPMN `INCLUSIVE`). */
    INCLUSIVE,
}

/**
 * Gateway-specific routing configuration for a [TokenFlowNode] of kind
 * [TokenNodeKind.GATEWAY].
 *
 * @property converging `true` if this gateway synchronises/merges incoming branches.
 * @property diverging `true` if this gateway splits into outgoing branches.
 *   Both flags may be `true` at once (a BPMN `MIXED`-direction gateway) — see
 *   [TokenFlowEngine]'s firing semantics for how that single firing event
 *   converges and then diverges without ever being split into two nodes.
 * @property defaultEdgeId Outgoing edge taken when no other condition matches
 *   (BPMN "default flow"). Skipped during the normal condition-evaluation pass
 *   and only used as the fallback.
 */
public data class GatewaySpec(
    public val family: GatewayFamily,
    public val converging: Boolean,
    public val diverging: Boolean,
    public val defaultEdgeId: String? = null,
)

/** A node in a [TokenFlowSpec]. */
public data class TokenFlowNode(
    public val id: String,
    public val kind: TokenNodeKind,
    /** Non-null if and only if [kind] is [TokenNodeKind.GATEWAY]. */
    public val gateway: GatewaySpec? = null,
    public val name: String? = null,
    /** Raw action-body string for [TokenNodeKind.ACTION] nodes; null for every other kind. */
    public val actionBody: String? = null,
)

/** A directed edge in a [TokenFlowSpec]. */
public data class TokenFlowEdge(
    public val id: String,
    public val sourceNodeId: String,
    public val targetNodeId: String,
    /** Raw guard/condition expression; null or blank = unconditionally true. */
    public val guard: String? = null,
    /** BPMN "default flow" marker — skipped during condition evaluation, used only as fallback. */
    public val isDefault: Boolean = false,
    public val isObjectFlow: Boolean = false,
    public val objectType: String? = null,
)

/** Severity of a [TokenFlowIssue]. */
public enum class TokenFlowSeverity { ERROR, WARNING }

/**
 * A structural problem found by [TokenFlowSpec.validate] (or flagged by an
 * adapter while building the spec, e.g. an unsupported BPMN gateway type).
 */
public data class TokenFlowIssue(
    public val severity: TokenFlowSeverity,
    public val code: String,
    public val message: String,
    public val elementId: String? = null,
)

/**
 * Model-agnostic, immutable specification of an executable token-flow graph
 * (ADR-0015). Built by an adapter ([dev.kuml.runtime.tokenflow.adapter]) from
 * a UML Activity diagram, a BPMN process, or (via
 * `dev.kuml.runtime.activity.ActivityRuntimeSpec.toTokenFlowSpec`) a SysML 2
 * ACT diagram — [TokenFlowEngine] itself has no knowledge of which metamodel
 * produced the spec.
 */
public class TokenFlowSpec private constructor(
    public val id: String,
    public val name: String,
    public val nodes: Map<String, TokenFlowNode>,
    public val edges: List<TokenFlowEdge>,
    private val structuralIssues: List<TokenFlowIssue>,
) {
    /** Outgoing edges per node id, in declaration order (deterministic firing — see ADR-0015 SF-15). */
    public val outgoing: Map<String, List<TokenFlowEdge>> = edges.groupBy { it.sourceNodeId }

    /** Incoming edges per node id, in declaration order. */
    public val incoming: Map<String, List<TokenFlowEdge>> = edges.groupBy { it.targetNodeId }

    /**
     * For every INCLUSIVE converging gateway: the set of node ids from which
     * that gateway is reachable without passing through it. Precomputed once
     * so the OR-join readiness check (`instance.tokenCounts.keys.none { it in
     * upstream }`) is O(|tokens|) at runtime instead of re-walking the graph
     * on every check. See [TokenFlowEngine] KDoc for the OR-join algorithm.
     */
    internal val inclusiveJoinUpstream: Map<String, Set<String>> = computeInclusiveJoinUpstream()

    private fun computeInclusiveJoinUpstream(): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        val inclusiveJoins =
            nodes.values.filter {
                it.kind == TokenNodeKind.GATEWAY && it.gateway?.family == GatewayFamily.INCLUSIVE && it.gateway.converging
            }
        for (join in inclusiveJoins) {
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            incoming[join.id].orEmpty().forEach { queue.addLast(it.sourceNodeId) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (n == join.id) continue
                if (!visited.add(n)) continue
                incoming[n].orEmpty().forEach { queue.addLast(it.sourceNodeId) }
            }
            result[join.id] = visited
        }
        return result
    }

    private fun computeReachable(): Set<String> {
        val initials = nodes.values.filter { it.kind == TokenNodeKind.INITIAL }.map { it.id }
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        initials.forEach { if (visited.add(it)) queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            outgoing[n].orEmpty().forEach { edge ->
                if (visited.add(edge.targetNodeId)) queue.addLast(edge.targetNodeId)
            }
        }
        return visited
    }

    /**
     * Validates structural well-formedness. ERROR issues mean the spec cannot
     * be executed at all (callers should refuse to run it); WARNING issues
     * describe likely-unintended modelling but do not block execution.
     */
    public fun validate(): List<TokenFlowIssue> {
        val issues = mutableListOf<TokenFlowIssue>()
        issues += structuralIssues

        if (nodes.values.none { it.kind == TokenNodeKind.INITIAL }) {
            issues +=
                TokenFlowIssue(
                    severity = TokenFlowSeverity.ERROR,
                    code = "NO_INITIAL_NODE",
                    message = "Token-flow model '$name' has no INITIAL node (UML Initial / BPMN Start Event).",
                )
        }

        for (edge in edges) {
            if (edge.sourceNodeId !in nodes) {
                issues +=
                    TokenFlowIssue(
                        severity = TokenFlowSeverity.ERROR,
                        code = "DANGLING_EDGE",
                        message = "Edge '${edge.id}' references unknown source node '${edge.sourceNodeId}'.",
                        elementId = edge.id,
                    )
            }
            if (edge.targetNodeId !in nodes) {
                issues +=
                    TokenFlowIssue(
                        severity = TokenFlowSeverity.ERROR,
                        code = "DANGLING_EDGE",
                        message = "Edge '${edge.id}' references unknown target node '${edge.targetNodeId}'.",
                        elementId = edge.id,
                    )
            }
        }

        val reachable = computeReachable()
        for (node in nodes.values) {
            if (node.id !in reachable) {
                issues +=
                    TokenFlowIssue(
                        severity = TokenFlowSeverity.WARNING,
                        code = "UNREACHABLE_NODE",
                        message = "Node '${node.id}' is not reachable from any INITIAL node.",
                        elementId = node.id,
                    )
            }
        }

        for (node in nodes.values.filter { it.kind == TokenNodeKind.GATEWAY }) {
            val g = node.gateway ?: continue
            val outs = outgoing[node.id].orEmpty()

            if (!g.converging && !g.diverging) {
                issues +=
                    TokenFlowIssue(
                        severity = TokenFlowSeverity.WARNING,
                        code = "GATEWAY_PASS_THROUGH",
                        message = "Gateway '${node.id}' is neither converging nor diverging; treated as pass-through.",
                        elementId = node.id,
                    )
            }

            if (g.diverging && (g.family == GatewayFamily.EXCLUSIVE || g.family == GatewayFamily.INCLUSIVE)) {
                val hasDefault = outs.any { it.isDefault }
                val hasUnguarded = outs.any { !it.isDefault && it.guard.isNullOrBlank() }
                if (outs.isNotEmpty() && !hasDefault && !hasUnguarded) {
                    val kindLabel = if (g.family == GatewayFamily.EXCLUSIVE) "Exclusive" else "Inclusive"
                    issues +=
                        TokenFlowIssue(
                            severity = TokenFlowSeverity.WARNING,
                            code = "NO_DEFAULT_BRANCH",
                            message =
                                "$kindLabel split '${node.id}' has no default edge and no unguarded edge — " +
                                    "a token may be lost if no guard matches at runtime.",
                            elementId = node.id,
                        )
                }
            }

            if (g.diverging && g.family == GatewayFamily.PARALLEL) {
                if (outs.any { !it.guard.isNullOrBlank() }) {
                    issues +=
                        TokenFlowIssue(
                            severity = TokenFlowSeverity.WARNING,
                            code = "PARALLEL_SPLIT_GUARD_IGNORED",
                            message =
                                "Parallel split '${node.id}' has guarded outgoing edges — " +
                                    "guards are ignored on a PARALLEL diverge (BPMN 2.0 §13.3.2); all branches always fire.",
                            elementId = node.id,
                        )
                }
            }
        }

        return issues
    }

    public companion object {
        /**
         * Builds a [TokenFlowSpec]. Duplicate node/edge ids are flagged as
         * ERROR issues (surfaced via [validate]) rather than thrown — callers
         * that only inspect the spec's shape (e.g. adapter tests) don't need
         * to handle an exception for a condition [validate] already reports.
         *
         * @param extraIssues Issues an adapter already knows about while
         *   building the spec (e.g. an unsupported BPMN gateway type it could
         *   not represent as a [GatewaySpec] at all) — merged into the result
         *   of [validate].
         */
        public fun of(
            id: String,
            name: String,
            nodes: List<TokenFlowNode>,
            edges: List<TokenFlowEdge>,
            extraIssues: List<TokenFlowIssue> = emptyList(),
        ): TokenFlowSpec {
            val structural = mutableListOf<TokenFlowIssue>()
            structural += extraIssues

            val seenNodeIds = mutableSetOf<String>()
            for (n in nodes) {
                if (!seenNodeIds.add(n.id)) {
                    structural +=
                        TokenFlowIssue(
                            severity = TokenFlowSeverity.ERROR,
                            code = "DUPLICATE_NODE_ID",
                            message = "Duplicate node id '${n.id}'.",
                            elementId = n.id,
                        )
                }
            }
            val seenEdgeIds = mutableSetOf<String>()
            for (e in edges) {
                if (!seenEdgeIds.add(e.id)) {
                    structural +=
                        TokenFlowIssue(
                            severity = TokenFlowSeverity.ERROR,
                            code = "DUPLICATE_EDGE_ID",
                            message = "Duplicate edge id '${e.id}'.",
                            elementId = e.id,
                        )
                }
            }

            return TokenFlowSpec(
                id = id,
                name = name,
                nodes = nodes.associateBy { it.id },
                edges = edges,
                structuralIssues = structural,
            )
        }
    }
}
