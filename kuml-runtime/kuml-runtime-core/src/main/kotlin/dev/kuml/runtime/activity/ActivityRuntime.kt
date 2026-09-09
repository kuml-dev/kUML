package dev.kuml.runtime.activity

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.snapshot.ActivityInstanceSnapshot
import dev.kuml.runtime.snapshot.MigrationException
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.runtime.snapshot.fingerprintActivity
import dev.kuml.sysml2.ActivityNodeKind

/**
 * Token-flow interpreter for an [ActivityRuntimeSpec].
 *
 * ## Semantics (MVP — V2.0.18)
 *
 *  - **Initial** node: receives one token at start → immediately fires, placing
 *    tokens on all outgoing edges' targets.
 *  - **Action** node: fires when it has at least one token; consumes one token,
 *    emits [TraceEntry.ActivityActionInvoked], places tokens on all outgoing targets.
 *  - **Decision** node: fires on first incoming token, evaluates outgoing guards
 *    in registration order, takes the first true (or unguarded) branch;
 *    emits [TraceEntry.DecisionTaken].
 *  - **Merge** node: passes through any incoming token immediately (no sync).
 *  - **Fork** node: consumes one token, places one on each outgoing target;
 *    emits [TraceEntry.ForkSplit].
 *  - **Join** node: waits until each unique source node of incoming edges has
 *    contributed at least one token; when all are satisfied, consumes one token
 *    (the accumulated join count), places one on each outgoing target; emits
 *    [TraceEntry.JoinReached] with `isReady=true` when firing.
 *  - **FlowFinal** node: consumes the token and emits [TraceEntry.FlowFinalConsumed];
 *    does NOT terminate the overall activity (other tokens continue).
 *  - **Final** (ActivityFinal) node: consumes all remaining tokens, marks
 *    instance as terminated, emits [TraceEntry.ActivityTerminated].
 *
 * ## Join tracking
 *
 * Join synchronisation uses `ActivityInstance.joinTokensReceived`:
 * `joinNodeId → Set<sourceNodeId>`. When a token travels along an edge whose
 * target is a Join, the source node id is recorded. The join fires when all
 * distinct source-node ids of its incoming edges are represented in the set.
 *
 * ## Determinism
 *
 * Firing order in each step is determined by sorting ready node ids
 * lexicographically before each step. This ensures reproducible traces
 * for the same model + events.
 *
 * ## V2.x deferred
 *
 * ObjectFlow typed payload, parallel-composition semantics beyond Fork/Join,
 * interruptible regions.
 */
public class ActivityRuntime(
    public val spec: ActivityRuntimeSpec,
    private val guardEvaluator: GuardEvaluator = ActivityGuardEvaluator(),
) {
    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Start execution: place a token on every Initial node, then immediately
     * step through them (Initial nodes are transient pseudo-states).
     * Returns the initial [ActivityInstance] + start trace.
     */
    public fun start(eventContext: Map<String, Any> = emptyMap()): Pair<ActivityInstance, List<TraceEntry>> {
        val initialNodes = spec.nodes.values.filter { it.kind == ActivityNodeKind.Initial }
        require(initialNodes.isNotEmpty()) {
            "Activity has no Initial node. " +
                "At least one ActionDefinition with kind=Initial is required."
        }

        val trace = mutableListOf<TraceEntry>()
        var instance = ActivityInstance()

        // Place tokens on all Initial nodes (not via edges, so no join-tracking needed)
        for (node in initialNodes.sortedBy { it.id }) {
            instance = instance.withTokenAt(node.id)
            trace += mkTokenPlaced(nodeId = node.id, clock = instance.clock)
        }

        // Immediately fire Initial nodes (they are transient)
        val (stepped, stepTrace) = step(instance = instance, eventContext = eventContext)
        instance = stepped
        trace += stepTrace

        return instance to trace
    }

    /**
     * Advance execution: fire all ready nodes in lexicographic order.
     * Returns the updated instance and the trace entries produced in this step.
     *
     * Returns the same instance unchanged if nothing can fire (deadlock or
     * already terminated).
     */
    public fun step(
        instance: ActivityInstance,
        eventContext: Map<String, Any> = emptyMap(),
    ): Pair<ActivityInstance, List<TraceEntry>> {
        if (instance.isTerminated) return instance to emptyList()

        val trace = mutableListOf<TraceEntry>()
        var current = instance

        val readyNodeIds = findReadyNodes(current).sorted()
        if (readyNodeIds.isEmpty()) return current to emptyList()

        for (nodeId in readyNodeIds) {
            if (current.isTerminated) break
            val nodeSpec = spec.nodes[nodeId] ?: continue
            // Re-check readiness after each firing (tokens may have changed)
            if (!isReady(instance = current, nodeId = nodeId)) continue
            val (fired, nodeTrace) = fireNode(instance = current, node = nodeSpec, eventContext = eventContext)
            current = fired
            trace += nodeTrace
        }

        // Advance logical clock once per step
        current = current.incrementClock()

        return current to trace
    }

    /**
     * Run until termination or [maxSteps] steps (default 1000 — guard against
     * infinite loops). Returns the final instance + full trace.
     *
     * @throws ActivityDeadlockException if the activity halts without reaching
     *   a Final node and [failOnDeadlock] is true.
     */
    public fun run(
        initial: ActivityInstance = start().first,
        eventContext: Map<String, Any> = emptyMap(),
        maxSteps: Int = 1000,
        failOnDeadlock: Boolean = true,
    ): Pair<ActivityInstance, List<TraceEntry>> {
        val trace = mutableListOf<TraceEntry>()
        var instance = initial
        var steps = 0

        while (!instance.isTerminated && steps < maxSteps) {
            val (stepped, stepTrace) = step(instance = instance, eventContext = eventContext)
            if (stepTrace.isEmpty() && !stepped.isTerminated) {
                // Nothing fired — deadlock
                if (failOnDeadlock) {
                    throw ActivityDeadlockException(
                        "Activity deadlocked at step $steps. " +
                            "Token distribution: ${instance.tokenCounts}. " +
                            "No enabled node found and Final node not reached.",
                    )
                }
                break
            }
            trace += stepTrace
            instance = stepped
            steps++
        }

        if (steps >= maxSteps && !instance.isTerminated) {
            throw ActivityDeadlockException(
                "Activity exceeded maxSteps=$maxSteps without reaching a Final node. " +
                    "Check for an infinite loop in the model. " +
                    "Token distribution at last step: ${instance.tokenCounts}.",
            )
        }

        return instance to trace
    }

    // ── full snapshot / restore (V2) ─────────────────────────────────────────

    /**
     * Erstellt einen vollständigen [ActivityInstanceSnapshot] mit Modell-Fingerprint.
     *
     * @param instance Die aktuelle ActivityInstance.
     * @param modelId Identifier des Modells.
     * @param modelFingerprint SHA-256-Fingerprint des Modells (via [fingerprintActivity]).
     */
    public fun snapshotFull(
        instance: ActivityInstance,
        modelId: String,
        modelFingerprint: String,
    ): ActivityInstanceSnapshot =
        ActivityInstanceSnapshot(
            modelId = modelId,
            modelFingerprint = modelFingerprint,
            instance = instance,
        )

    /**
     * Stellt eine [ActivityInstance] aus einem [ActivityInstanceSnapshot] wieder her.
     * Prüft Kompatibilität via [policy] vor der Wiederherstellung.
     *
     * @throws dev.kuml.runtime.snapshot.MigrationException wenn [policy] den
     *   Snapshot ablehnt.
     */
    public fun restoreFrom(
        snapshot: ActivityInstanceSnapshot,
        policy: MigrationPolicy = MigrationPolicy.Reject,
    ): ActivityInstance {
        // Security review (feature/tokenflow-execution-engine, finding "KDoc verweist auf eine
        // Schutzmassnahme, die es nicht gibt"): ActivityInstance.joinEdgeTokens' KDoc documents "a
        // snapshot must not be restored by the other engine" as an existing restriction, but
        // nothing enforced it before this check — isJoinReady below reads only
        // joinTokensReceived, so a snapshot with joinEdgeTokens populated (written by
        // dev.kuml.runtime.tokenflow.TokenFlowEngine, were it ever to gain a snapshot path) would
        // have its AND-join edge arrivals silently ignored, leaving those tokens permanently stuck
        // at the join (isJoinReady demands every incoming edge again, which never happens because
        // this engine never populates joinTokensReceived from joinEdgeTokens). Unconditional, like
        // onPatch's vertex-removal check — not policy-gated, because no MigrationPolicy question
        // ("did the model change acceptably") applies to a structurally wrong snapshot kind.
        if (snapshot.instance.joinEdgeTokens.isNotEmpty()) {
            throw MigrationException(
                reason = "snapshot was written by the TokenFlowEngine (joinEdgeTokens populated) and cannot be restored by ActivityRuntime",
                expected = "joinEdgeTokens empty",
                actual = "joinEdgeTokens has ${snapshot.instance.joinEdgeTokens.size} join node(s) with edge-level arrivals",
            )
        }

        val currentFingerprint =
            fingerprintActivity(
                nodeIds = spec.nodes.keys,
                edgeIds = spec.edges.map { it.id }.toSet(),
            )
        val currentVertexIds = spec.nodes.keys

        val snapshotVertexIds =
            snapshot.instance.tokenCounts.keys
                .toList()
        policy.check(
            snapshotFingerprint = snapshot.modelFingerprint,
            currentFingerprint = currentFingerprint,
            snapshotVertexIds = snapshotVertexIds,
            currentVertexIds = currentVertexIds,
        )

        return snapshot.instance
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** Find all node ids that are currently ready to fire. */
    private fun findReadyNodes(instance: ActivityInstance): List<String> =
        instance.tokenCounts.keys.filter { nodeId -> isReady(instance = instance, nodeId = nodeId) }

    /**
     * A node is ready if it has at least one token AND (for Join) all incoming
     * source nodes have contributed.
     */
    private fun isReady(
        instance: ActivityInstance,
        nodeId: String,
    ): Boolean {
        if ((instance.tokenCounts[nodeId] ?: 0) < 1) return false
        val nodeSpec = spec.nodes[nodeId] ?: return false
        return when (nodeSpec.kind) {
            ActivityNodeKind.Join -> isJoinReady(instance = instance, joinId = nodeId)
            else -> true
        }
    }

    /**
     * A Join is ready when every distinct source node of its incoming edges
     * has contributed at least one token to this join's received-set.
     */
    private fun isJoinReady(
        instance: ActivityInstance,
        joinId: String,
    ): Boolean {
        val incomingSourceIds = incomingEdges(joinId).map { it.sourceNodeId }.toSet()
        if (incomingSourceIds.isEmpty()) return true
        val received = instance.joinTokensReceived[joinId] ?: emptySet()
        return incomingSourceIds.all { it in received }
    }

    /**
     * Place a token at [targetNodeId] originating from [fromNodeId] (along edge [edge]).
     * For Join nodes, also records the source in [ActivityInstance.joinTokensReceived].
     * For other nodes, just increments the token count.
     */
    private fun placeTokenViaEdge(
        inst: ActivityInstance,
        edge: ActivityEdgeSpec,
        trace: MutableList<TraceEntry>,
    ): ActivityInstance {
        val targetId = edge.targetNodeId
        val sourceId = edge.sourceNodeId
        val targetKind = spec.nodes[targetId]?.kind
        var updated = inst.withTokenAt(targetId)
        trace += mkTokenPlaced(nodeId = targetId, clock = inst.clock)

        // If target is a Join, record the source contribution
        if (targetKind == ActivityNodeKind.Join) {
            val currentReceived = updated.joinTokensReceived[targetId] ?: emptySet()
            val newReceived = currentReceived + sourceId
            updated = updated.copy(joinTokensReceived = updated.joinTokensReceived + (targetId to newReceived))
        }
        return updated
    }

    /** Fire a single node; returns updated instance + trace entries. */
    private fun fireNode(
        instance: ActivityInstance,
        node: ActivityNodeSpec,
        eventContext: Map<String, Any>,
    ): Pair<ActivityInstance, List<TraceEntry>> {
        val trace = mutableListOf<TraceEntry>()
        var inst = instance
        val clock = inst.clock

        when (node.kind) {
            ActivityNodeKind.Initial -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                for (edge in outgoingEdges(node.id)) {
                    inst = placeTokenViaEdge(inst = inst, edge = edge, trace = trace)
                }
            }

            ActivityNodeKind.Action -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                trace +=
                    TraceEntry.ActivityActionInvoked(
                        seqNo = clock,
                        timestamp = "",
                        nodeId = node.id,
                        body = node.actionBody,
                        clock = clock,
                    )
                for (edge in outgoingEdges(node.id)) {
                    inst = placeTokenViaEdge(inst = inst, edge = edge, trace = trace)
                }
            }

            ActivityNodeKind.Decision -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                val outEdges = outgoingEdges(node.id)
                val chosenEdge =
                    outEdges.firstOrNull { edge ->
                        val g = edge.guard
                        if (g.isNullOrBlank()) {
                            true
                        } else {
                            evaluateGuard(guard = g, eventContext = eventContext) == GuardResult.True
                        }
                    }
                if (chosenEdge != null) {
                    trace +=
                        TraceEntry.DecisionTaken(
                            seqNo = clock,
                            timestamp = "",
                            nodeId = node.id,
                            chosenEdgeId = chosenEdge.id,
                            guard = chosenEdge.guard,
                            clock = clock,
                        )
                    inst = placeTokenViaEdge(inst = inst, edge = chosenEdge, trace = trace)
                }
                // If no branch matches: token consumed, no new token placed
                // (model error — not thrown in MVP per plan)
            }

            ActivityNodeKind.Merge -> {
                // Pass-through: consume, forward to all outgoing
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                for (edge in outgoingEdges(node.id)) {
                    inst = placeTokenViaEdge(inst = inst, edge = edge, trace = trace)
                }
            }

            ActivityNodeKind.Fork -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                val outEdges = outgoingEdges(node.id)
                val targetIds = outEdges.map { it.targetNodeId }
                trace +=
                    TraceEntry.ForkSplit(
                        seqNo = clock,
                        timestamp = "",
                        nodeId = node.id,
                        targetNodeIds = targetIds,
                        clock = clock,
                    )
                for (edge in outEdges) {
                    inst = placeTokenViaEdge(inst = inst, edge = edge, trace = trace)
                }
            }

            ActivityNodeKind.Join -> {
                // Consume the join-accumulated token (count == number of sources that fired)
                // We consume ALL tokens at the join (one per contributing source), then
                // place one token on each outgoing edge.
                val incomingSourceIds = incomingEdges(node.id).map { it.sourceNodeId }.toSet()
                val numTokens = incomingSourceIds.size.coerceAtLeast(1)
                // Consume all accumulated tokens at the join
                var updated = inst
                repeat(numTokens) { updated = updated.withoutTokenAt(node.id) }
                // Clear join tracking
                val newJoinTracking = updated.joinTokensReceived - node.id
                inst = updated.copy(joinTokensReceived = newJoinTracking)

                trace += mkTokenConsumed(nodeId = node.id, clock = clock)

                val awaitingEdgeIds = incomingEdges(node.id).map { it.id }
                trace +=
                    TraceEntry.JoinReached(
                        seqNo = clock,
                        timestamp = "",
                        nodeId = node.id,
                        awaitingEdgeIds = awaitingEdgeIds,
                        isReady = true,
                        clock = clock,
                    )

                for (edge in outgoingEdges(node.id)) {
                    inst = placeTokenViaEdge(inst = inst, edge = edge, trace = trace)
                }
            }

            ActivityNodeKind.FlowFinal -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                trace +=
                    TraceEntry.FlowFinalConsumed(
                        seqNo = clock,
                        timestamp = "",
                        nodeId = node.id,
                        clock = clock,
                    )
                // FlowFinal does NOT terminate the activity
            }

            ActivityNodeKind.Final -> {
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                // Consume all tokens (terminate the activity)
                inst = inst.copy(tokenCounts = emptyMap(), isTerminated = true, joinTokensReceived = emptyMap())
                trace +=
                    TraceEntry.ActivityTerminated(
                        seqNo = clock,
                        timestamp = "",
                        clock = clock,
                    )
            }
        }

        return inst to trace
    }

    /** Collect all outgoing edges from [nodeId]. */
    private fun outgoingEdges(nodeId: String): List<ActivityEdgeSpec> = spec.edges.filter { it.sourceNodeId == nodeId }

    /** Collect all incoming edges to [nodeId]. */
    private fun incomingEdges(nodeId: String): List<ActivityEdgeSpec> = spec.edges.filter { it.targetNodeId == nodeId }

    /**
     * Evaluate a guard expression against the event context.
     * A genuinely false-evaluating guard, or one whose variable is missing,
     * returns [GuardResult.False]; a guard the evaluator could not parse or
     * evaluate at all returns [GuardResult.Failed] instead of throwing — this
     * branch never propagates an exception (per plan: don't throw). Branch
     * selection above only compares `== GuardResult.True`, so `False` and
     * `Failed` both mean "this edge is not taken". **On this path a `Failed`
     * is not surfaced anywhere**: [ActivityRuntime] writes no trace entry
     * (`TraceEntry.GuardWarning` is only ever produced by
     * [dev.kuml.runtime.StateMachineRuntime]), has no listener of its own,
     * and does not log. The `guardResultListener` side-channel belongs to the
     * separate `dev.kuml.runtime.tokenflow.TokenFlowEngine` engine; a `Failed`
     * produced here never reaches it. See
     * `docs/handbook/modules/reference/pages/ocl.adoc` (fail-closed guards
     * section) for the authoritative per-path breakdown.
     *
     * **Security fix (ADR-0015 / B2, was dead code before):** this now actually
     * routes through the injected [guardEvaluator] instead of calling
     * [dev.kuml.core.ocl.OclExpressions.evaluate] directly. A caller that wraps
     * the evaluator in `dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator` can
     * therefore bound guard-evaluation time — previously that wrapper had no
     * effect on [ActivityRuntime] because the constructor parameter was never
     * consulted at all.
     *
     * The evaluation environment (built by the default [ActivityGuardEvaluator])
     * allows bare-identifier guards (`"valid"`, `"!valid"`) to keep working:
     * the eventContext entries are merged directly into the evaluation env so
     * `valid` resolves to `env["valid"]`.
     */
    private fun evaluateGuard(
        guard: String,
        eventContext: Map<String, Any>,
    ): GuardResult {
        val syntheticInstance = ActivityEvalContext(eventContext)
        return guardEvaluator.evaluate(guard = guard, instance = syntheticInstance, event = Event.of("advance"))
    }

    // ── trace entry factories ─────────────────────────────────────────────────

    private fun mkTokenPlaced(
        nodeId: String,
        clock: Long,
    ): TraceEntry.TokenPlaced = TraceEntry.TokenPlaced(seqNo = clock, timestamp = "", nodeId = nodeId, clock = clock)

    private fun mkTokenConsumed(
        nodeId: String,
        clock: Long,
    ): TraceEntry.TokenConsumed = TraceEntry.TokenConsumed(seqNo = clock, timestamp = "", nodeId = nodeId, clock = clock)
}

/**
 * Thrown when an activity halts without reaching a Final node (deadlock) or
 * when [ActivityRuntime.run] exceeds its maxSteps guard.
 */
public class ActivityDeadlockException(
    message: String,
) : RuntimeException(message)

// ── Guard evaluation context shim ────────────────────────────────────────────

/**
 * Minimal [ModelInstance] shim used when evaluating Decision guards inside
 * [ActivityRuntime]. The [variables] map is populated from the event context
 * so OCL expressions like `event.allow` and `!valid` work against the context.
 */
private class ActivityEvalContext(
    eventContext: Map<String, Any>,
) : ModelInstance<ActivityRuntimeSpec> {
    override val model: ActivityRuntimeSpec get() = error("ActivityEvalContext.model should not be accessed")
    override val currentVertices: List<dev.kuml.uml.UmlVertex> = emptyList()
    override val variables: MutableMap<String, Any?> = eventContext.toMutableMap()
    override val isTerminated: Boolean = false
    override val trace: List<TraceEntry> = emptyList()
}
