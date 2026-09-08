package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.activity.ActivityInstance
import dev.kuml.runtime.activity.clearJoinArrivals
import dev.kuml.runtime.activity.consumeJoinArrivals
import dev.kuml.runtime.activity.incrementClock
import dev.kuml.runtime.activity.totalTokens
import dev.kuml.runtime.activity.withJoinArrival
import dev.kuml.runtime.activity.withTokenAt
import dev.kuml.runtime.activity.withoutTokenAt
import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator

/** Variable bindings visible to gateway guards during a run (ADR-0015). */
public data class TokenFlowContext(
    public val variables: Map<String, Any?> = emptyMap(),
) {
    public companion object {
        public val EMPTY: TokenFlowContext = TokenFlowContext()
    }
}

/** Paradigm-neutral-ish result classification of a [TokenFlowEngine] step or run. */
public sealed interface TokenFlowOutcome {
    /** At least one node fired and the activity is still running. */
    public data object Advanced : TokenFlowOutcome

    /** Nothing fired in this step (either no ready node, or `enabled()` was already empty). */
    public data object Idle : TokenFlowOutcome

    /** The activity terminated — either a TERMINATE_FINAL fired, or the marking became empty. */
    public data object Terminated : TokenFlowOutcome

    /** No node could fire and the marking is non-empty — a genuine deadlock. */
    public data class Blocked(
        public val reason: String,
    ) : TokenFlowOutcome

    /** A [TokenFlowLimits] threshold was exceeded. [limit] is one of "maxSteps"/"maxTokens"/"maxTraceEntries"/"wallClock". */
    public data class LimitExceeded(
        public val limit: String,
        public val detail: String,
    ) : TokenFlowOutcome

    /** A hard modelling error (e.g. `strictGuardCoverage` violation) that execution cannot recover from. */
    public data class Failed(
        public val message: String,
    ) : TokenFlowOutcome
}

/** Result of one [TokenFlowEngine.start] / [TokenFlowEngine.step] / [TokenFlowEngine.run] / [TokenFlowEngine.fireNode] call. */
public data class TokenFlowStep(
    public val instance: ActivityInstance,
    public val trace: List<TraceEntry>,
    public val outcome: TokenFlowOutcome,
)

/**
 * AutoCloseable wrapper returned by [TokenFlowEngine.sandboxed] — the only
 * public construction path for a [TokenFlowEngine]. Closes the guard
 * evaluator's thread pool; see `TimeLimitedGuardEvaluator` KDoc (daemon
 * threads make this safe-but-wasteful to skip, never leaking non-daemon
 * threads).
 */
public class SandboxedTokenFlowEngine internal constructor(
    public val engine: TokenFlowEngine,
    private val evaluator: TimeLimitedGuardEvaluator,
) : AutoCloseable {
    override fun close() {
        evaluator.close()
    }
}

/**
 * Token-flow interpreter for a [TokenFlowSpec] — the ADR-0015 execution
 * engine shared by UML Activity, BPMN Process, and (via
 * `dev.kuml.runtime.activity.ActivityRuntimeSpec.toTokenFlowSpec`) SysML 2 ACT
 * diagrams.
 *
 * ## Firing semantics
 *
 * | Node kind | Ready when | On fire |
 * |---|---|---|
 * | INITIAL / ACTION / OBJECT_NODE | ≥1 token | consume 1, (ACTION: emit `ActivityActionInvoked`), place a token on every outgoing edge |
 * | FLOW_FINAL | ≥1 token | consume 1, emit `FlowFinalConsumed`; activity keeps running |
 * | END_EVENT | ≥1 token | consume 1; activity terminates only via the empty-marking postcondition below |
 * | TERMINATE_FINAL | ≥1 token | consume ALL tokens, terminate immediately |
 * | GATEWAY, EXCLUSIVE converging (Merge) | ≥1 token | pass through, no extra trace entry (parity with legacy Merge) |
 * | GATEWAY, PARALLEL converging (AND-Join) | every incoming edge has delivered ≥1 arrival | consume one arrival per edge, emit `JoinReached(isReady=true)` |
 * | GATEWAY, INCLUSIVE converging (OR-Join) | ≥1 arrival AND no token anywhere upstream of it (see below) | consume all arrived tokens, emit `JoinReached(isReady=true)` |
 * | GATEWAY, EXCLUSIVE diverging (Decision/XOR-split) | — | first non-default edge whose guard is true wins; else the default edge; else (per [TokenFlowOptions.strictGuardCoverage]) fail or drop the token |
 * | GATEWAY, PARALLEL diverging (Fork/AND-split) | — | every outgoing edge gets a token; guards on these edges are ignored (BPMN 2.0 §13.3.2) |
 * | GATEWAY, INCLUSIVE diverging (OR-split) | — | every edge whose guard is true gets a token; if none, the default edge; else per `strictGuardCoverage` |
 * | GATEWAY, MIXED (`converging && diverging`) | per its converging rule | converges, then diverges — **in the same firing**, no synthetic split into two nodes |
 *
 * A GATEWAY with neither `converging` nor `diverging` set behaves as a plain
 * pass-through (same as EXCLUSIVE converging).
 *
 * ## Global postcondition
 *
 * If the marking becomes empty after a step without a TERMINATE_FINAL having
 * fired, the activity is considered to have ended normally: `isTerminated`
 * becomes `true` and an `ActivityTerminated` entry is emitted. Without this
 * rule, a BPMN process that ends cleanly at its (non-terminate) end event
 * would be reported as deadlocked.
 *
 * ## OR-join algorithm
 *
 * An INCLUSIVE converging gateway `j` is ready once at least one token has
 * arrived AND no token currently held anywhere in the instance sits upstream
 * of `j` (i.e. could still reach `j` without passing through it) — see
 * [TokenFlowSpec.inclusiveJoinUpstream], computed once per spec via a reverse
 * BFS. This is a *structural* approximation (it ignores whether guards would
 * actually let a pending token reach `j`): it can wait longer than strictly
 * necessary, but it can never fire too early and orphan a token. A genuine
 * deadlock in this situation still surfaces via the "nothing fired" check in
 * [run].
 *
 * ## Determinism
 *
 * Ready node ids are sorted lexicographically before each step, and
 * [TokenFlowSpec.outgoing] / [TokenFlowSpec.incoming] preserve edge
 * declaration order (`List.groupBy` is order-preserving), so the same model +
 * context always produces the same trace.
 *
 * ## Trace-format parity
 *
 * Emits exactly the [TraceEntry] variants [dev.kuml.runtime.activity.ActivityRuntime]
 * already emits (`TokenPlaced`, `TokenConsumed`, `ActivityActionInvoked`,
 * `DecisionTaken`, `ForkSplit`, `JoinReached`, `FlowFinalConsumed`,
 * `ActivityTerminated`) — no new `TraceEntry` subtypes. This keeps
 * `dev.kuml.io.svg.bpmn.smil.BpmnTokenTimelineBuilder` and
 * `dev.kuml.runtime.TraceFlavourDetector` working against this engine's
 * output without any change, and is exercised by the adapter/engine parity
 * tests against `ActivityRuntime`. `seqNo` is set equal to the (not strictly
 * monotone per-entry) logical `clock`, matching the legacy engine's
 * `TraceDiff`-index-based comparison contract — this is a known
 * inconsistency inherited intentionally for parity (see ADR-0015 SF-1), not
 * a defect in this engine.
 */
public class TokenFlowEngine internal constructor(
    public val spec: TokenFlowSpec,
    private val guards: GuardEvaluator,
    private val limits: TokenFlowLimits = TokenFlowLimits(),
    private val options: TokenFlowOptions = TokenFlowOptions(),
    private val nanoClock: () -> Long = System::nanoTime,
    /**
     * Security review (feature/tokenflow-execution-engine, finding "Guard-Timeout und
     * Guard-Exception sind von 'Guard ist false' nicht unterscheidbar"): invoked with the raw
     * [GuardResult] every time an edge guard is evaluated during a diverge, *before* it is
     * collapsed to the boolean [evaluateEdgeGuard] returns to the firing logic. Deliberately kept
     * out of [TraceEntry] / the trace format — [TokenFlowStep.trace] must stay restricted to the
     * exact variants [dev.kuml.runtime.activity.ActivityRuntime] emits (see "Trace-format parity"
     * above); adding a new variant would risk `TraceFlavourDetector` reclassifying an otherwise
     * pure BPMN/ACT trace as `MIXED` the moment a guard ever times out, breaking `trace replay`
     * and `--expected` goldfile comparisons. This side channel lets a caller (e.g.
     * `SimulateCommand`) surface a [GuardResult.Failed] as a warning and map a sandbox timeout to
     * `ExitCodes.SANDBOX_TIMEOUT` without touching the trace at all. Called synchronously on the
     * engine's own thread (guard evaluation already blocks on it via
     * `TimeLimitedGuardEvaluator.evaluate`), so no synchronization is needed for a
     * caller-provided, single-threaded collector.
     */
    private val guardResultListener: (TokenFlowEdge, GuardResult) -> Unit = { _, _ -> },
) {
    // ── public API ───────────────────────────────────────────────────────────

    /** Places a token on every INITIAL node, then immediately steps once (INITIAL nodes are transient). */
    public fun start(context: TokenFlowContext = TokenFlowContext.EMPTY): TokenFlowStep {
        val initials =
            spec.nodes.values
                .filter { it.kind == TokenNodeKind.INITIAL }
                .sortedBy { it.id }
        if (initials.isEmpty()) {
            return TokenFlowStep(
                instance = ActivityInstance(),
                trace = emptyList(),
                outcome = TokenFlowOutcome.Failed("Token-flow model '${spec.name}' has no INITIAL node."),
            )
        }

        var instance = ActivityInstance()
        val trace = mutableListOf<TraceEntry>()
        for (node in initials) {
            instance = instance.withTokenAt(node.id)
            trace += mkTokenPlaced(nodeId = node.id, clock = instance.clock)
        }

        val stepResult = step(instance = instance, context = context)
        return TokenFlowStep(instance = stepResult.instance, trace = trace + stepResult.trace, outcome = stepResult.outcome)
    }

    /** Fires every currently-ready node once (lexicographic order), then advances the logical clock once. */
    public fun step(
        instance: ActivityInstance,
        context: TokenFlowContext = TokenFlowContext.EMPTY,
    ): TokenFlowStep {
        if (instance.isTerminated) return TokenFlowStep(instance = instance, trace = emptyList(), outcome = TokenFlowOutcome.Terminated)

        val ready = readyNodes(instance)
        if (ready.isEmpty()) return TokenFlowStep(instance = instance, trace = emptyList(), outcome = TokenFlowOutcome.Idle)

        val trace = mutableListOf<TraceEntry>()
        var current = instance
        for (nodeId in ready) {
            if (current.isTerminated) break
            val node = spec.nodes[nodeId] ?: continue
            if (!isReady(instance = current, nodeId = nodeId)) continue
            when (val result = fireNodeInternal(instance = current, node = node, context = context)) {
                is FireResult.Fired -> {
                    current = result.instance
                    trace += result.trace
                }
                is FireResult.Failed -> return TokenFlowStep(
                    instance = current,
                    trace = trace,
                    outcome = TokenFlowOutcome.Failed(result.message),
                )
            }
        }

        current = current.incrementClock()
        current = applyEmptyMarkingPostcondition(instance = current, trace = trace)

        val outcome = if (current.isTerminated) TokenFlowOutcome.Terminated else TokenFlowOutcome.Advanced
        return TokenFlowStep(instance = current, trace = trace, outcome = outcome)
    }

    /** Fires exactly one named node (used by `--interactive`); fails if it is not currently ready. */
    public fun fireNode(
        instance: ActivityInstance,
        nodeId: String,
        context: TokenFlowContext = TokenFlowContext.EMPTY,
    ): TokenFlowStep {
        if (instance.isTerminated) return TokenFlowStep(instance = instance, trace = emptyList(), outcome = TokenFlowOutcome.Terminated)
        val node =
            spec.nodes[nodeId]
                ?: return TokenFlowStep(
                    instance = instance,
                    trace = emptyList(),
                    outcome = TokenFlowOutcome.Failed("Unknown node id '$nodeId'."),
                )
        if (!isReady(instance = instance, nodeId = nodeId)) {
            return TokenFlowStep(
                instance = instance,
                trace = emptyList(),
                outcome = TokenFlowOutcome.Failed("Node '$nodeId' is not currently ready to fire."),
            )
        }

        return when (val result = fireNodeInternal(instance = instance, node = node, context = context)) {
            is FireResult.Failed ->
                TokenFlowStep(
                    instance = instance,
                    trace = emptyList(),
                    outcome = TokenFlowOutcome.Failed(result.message),
                )
            is FireResult.Fired -> {
                var current = result.instance.incrementClock()
                val trace = result.trace.toMutableList()
                current = applyEmptyMarkingPostcondition(instance = current, trace = trace)
                val outcome = if (current.isTerminated) TokenFlowOutcome.Terminated else TokenFlowOutcome.Advanced
                TokenFlowStep(instance = current, trace = trace, outcome = outcome)
            }
        }
    }

    /** Ids of nodes that are currently ready to fire, lexicographically sorted. */
    public fun enabledNodes(instance: ActivityInstance): List<String> = readyNodes(instance)

    /**
     * Runs [initial] to termination, applying [limits] after every step.
     * Never throws — limit breaches and deadlocks are reported via
     * [TokenFlowOutcome], together with the trace produced up to that point
     * (unlike `ActivityRuntime.run`, which discards the trace on
     * `ActivityDeadlockException`).
     */
    public fun run(
        initial: ActivityInstance,
        context: TokenFlowContext = TokenFlowContext.EMPTY,
    ): TokenFlowStep {
        var instance = initial
        val trace = mutableListOf<TraceEntry>()
        var steps = 0
        // Security review (feature/tokenflow-execution-engine, finding "Unvalidierte CLI-Limits
        // und nicht ueberlaufsichere Wall-Clock-Deadline"): an absolute `nanoClock() + budget`
        // deadline is exactly the comparison System.nanoTime()'s own contract warns against — it
        // is only meaningful as a *difference* between two calls, because the underlying counter
        // can wrap. `wallClockBudgetMs * 1_000_000L` could also overflow Long on an extreme
        // (now CLI-rejected, but TokenFlowLimits can still be constructed directly by any caller)
        // value before that addition ever happens. startNanos/elapsedNanos avoids both: the
        // multiplication is clamped instead of wrapped, and the loop compares an elapsed duration
        // rather than two absolute instants.
        val startNanos = nanoClock()
        val wallClockBudgetNanos = safeMillisToNanos(limits.wallClockBudgetMs)

        while (!instance.isTerminated) {
            if (steps >= limits.maxSteps) {
                return TokenFlowStep(
                    instance = instance,
                    trace = trace,
                    outcome =
                        TokenFlowOutcome.LimitExceeded(
                            limit = "maxSteps",
                            detail =
                                "Exceeded maxSteps=${limits.maxSteps} without reaching termination. " +
                                    "Token distribution: ${instance.tokenCounts}.",
                        ),
                )
            }
            if (nanoClock() - startNanos >= wallClockBudgetNanos) {
                return TokenFlowStep(
                    instance = instance,
                    trace = trace,
                    outcome =
                        TokenFlowOutcome.LimitExceeded(
                            limit = "wallClock",
                            detail = "Exceeded wall-clock budget of ${limits.wallClockBudgetMs} ms.",
                        ),
                )
            }

            val stepResult = step(instance = instance, context = context)
            trace += stepResult.trace
            instance = stepResult.instance

            when (val outcome = stepResult.outcome) {
                TokenFlowOutcome.Terminated -> return TokenFlowStep(
                    instance = instance,
                    trace = trace,
                    outcome = TokenFlowOutcome.Terminated,
                )
                TokenFlowOutcome.Idle ->
                    return if (options.failOnDeadlock) {
                        TokenFlowStep(
                            instance = instance,
                            trace = trace,
                            outcome =
                                TokenFlowOutcome.Blocked(
                                    "No enabled node found and no terminal node reached. Token distribution: ${instance.tokenCounts}.",
                                ),
                        )
                    } else {
                        TokenFlowStep(instance = instance, trace = trace, outcome = TokenFlowOutcome.Idle)
                    }
                is TokenFlowOutcome.Failed -> return TokenFlowStep(instance = instance, trace = trace, outcome = outcome)
                is TokenFlowOutcome.Blocked -> return TokenFlowStep(instance = instance, trace = trace, outcome = outcome)
                is TokenFlowOutcome.LimitExceeded -> return TokenFlowStep(instance = instance, trace = trace, outcome = outcome)
                TokenFlowOutcome.Advanced -> steps++
            }

            if (instance.totalTokens > limits.maxTokens) {
                return TokenFlowStep(
                    instance = instance,
                    trace = trace,
                    outcome =
                        TokenFlowOutcome.LimitExceeded(
                            limit = "maxTokens",
                            detail =
                                "Token count ${instance.totalTokens} exceeds maxTokens=${limits.maxTokens} — " +
                                    "likely a Fork inside a cycle.",
                        ),
                )
            }
            if (trace.size > limits.maxTraceEntries) {
                return TokenFlowStep(
                    instance = instance,
                    trace = trace,
                    outcome =
                        TokenFlowOutcome.LimitExceeded(
                            limit = "maxTraceEntries",
                            detail = "Trace size ${trace.size} exceeds maxTraceEntries=${limits.maxTraceEntries}.",
                        ),
                )
            }
        }

        return TokenFlowStep(instance = instance, trace = trace, outcome = TokenFlowOutcome.Terminated)
    }

    public companion object {
        /**
         * The only public construction path for a [TokenFlowEngine]: guard
         * evaluation is unconditionally wrapped in
         * `dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator`. There is no
         * overload that accepts a bare [GuardEvaluator] — ADR-0015 treats this
         * as a hard doctrine (see security fix B2: the legacy
         * `ActivityRuntime` had an unsandboxed default for years because
         * sandboxing was opt-in), enforced structurally rather than by
         * convention.
         */
        public fun sandboxed(
            spec: TokenFlowSpec,
            policy: SandboxPolicy = SandboxPolicy(),
            limits: TokenFlowLimits = TokenFlowLimits(),
            options: TokenFlowOptions = TokenFlowOptions(),
            guardResultListener: (TokenFlowEdge, GuardResult) -> Unit = { _, _ -> },
        ): SandboxedTokenFlowEngine {
            val evaluator = TimeLimitedGuardEvaluator(delegate = TokenFlowGuardEvaluator(), policy = policy)
            val engine =
                TokenFlowEngine(
                    spec = spec,
                    guards = evaluator,
                    limits = limits,
                    options = options,
                    guardResultListener = guardResultListener,
                )
            return SandboxedTokenFlowEngine(engine = engine, evaluator = evaluator)
        }
    }

    // ── private: readiness ──────────────────────────────────────────────────

    private fun readyNodes(instance: ActivityInstance): List<String> =
        instance.tokenCounts.keys
            .filter { isReady(instance = instance, nodeId = it) }
            .sorted()

    private fun isReady(
        instance: ActivityInstance,
        nodeId: String,
    ): Boolean {
        if ((instance.tokenCounts[nodeId] ?: 0) < 1) return false
        val node = spec.nodes[nodeId] ?: return false
        val g = node.gateway
        if (node.kind != TokenNodeKind.GATEWAY || g == null || !g.converging) return true

        return when (g.family) {
            GatewayFamily.EXCLUSIVE -> true
            GatewayFamily.PARALLEL -> {
                val incomingIds =
                    spec.incoming[nodeId]
                        .orEmpty()
                        .map { it.id }
                        .toSet()
                if (incomingIds.isEmpty()) return true
                val arrived = instance.joinEdgeTokens[nodeId]?.keys.orEmpty()
                incomingIds.all { it in arrived }
            }
            GatewayFamily.INCLUSIVE -> {
                val arrivals = instance.joinEdgeTokens[nodeId]
                if (arrivals.isNullOrEmpty()) return false
                val upstream = spec.inclusiveJoinUpstream[nodeId].orEmpty()
                instance.tokenCounts.keys.none { it in upstream }
            }
        }
    }

    // ── private: firing ──────────────────────────────────────────────────────

    private sealed interface FireResult {
        data class Fired(
            val instance: ActivityInstance,
            val trace: List<TraceEntry>,
        ) : FireResult

        data class Failed(
            val message: String,
        ) : FireResult
    }

    private fun fireNodeInternal(
        instance: ActivityInstance,
        node: TokenFlowNode,
        context: TokenFlowContext,
    ): FireResult {
        val trace = mutableListOf<TraceEntry>()
        var inst = instance
        val clock = inst.clock

        when (node.kind) {
            TokenNodeKind.INITIAL, TokenNodeKind.ACTION, TokenNodeKind.OBJECT_NODE -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                if (node.kind == TokenNodeKind.ACTION) {
                    trace +=
                        TraceEntry.ActivityActionInvoked(
                            seqNo = clock,
                            timestamp = "",
                            nodeId = node.id,
                            body = node.actionBody,
                            clock = clock,
                        )
                }
                for (edge in spec.outgoing[node.id].orEmpty()) {
                    inst = placeToken(instance = inst, edge = edge, trace = trace)
                }
            }

            TokenNodeKind.FLOW_FINAL -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                trace += TraceEntry.FlowFinalConsumed(seqNo = clock, timestamp = "", nodeId = node.id, clock = clock)
            }

            TokenNodeKind.END_EVENT -> {
                inst = inst.withoutTokenAt(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                // No per-node terminal trace entry here: whether the overall
                // activity ends is decided by the empty-marking postcondition
                // in step()/fireNode() after ALL ready nodes have fired.
            }

            TokenNodeKind.TERMINATE_FINAL -> {
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                inst =
                    inst.copy(tokenCounts = emptyMap(), isTerminated = true, joinTokensReceived = emptyMap(), joinEdgeTokens = emptyMap())
                trace += TraceEntry.ActivityTerminated(seqNo = clock, timestamp = "", clock = clock)
            }

            TokenNodeKind.GATEWAY -> {
                val g =
                    node.gateway
                        ?: return FireResult.Failed("Gateway node '${node.id}' has no GatewaySpec.")
                when {
                    g.converging && g.diverging -> {
                        inst = consumeConvergence(instance = inst, node = node, g = g, trace = trace)
                        inst =
                            diverge(instance = inst, node = node, g = g, context = context, trace = trace)
                                ?: return FireResult.Failed(strictCoverageMessage(node.id))
                    }
                    g.diverging -> {
                        inst = inst.withoutTokenAt(node.id)
                        trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                        inst =
                            diverge(instance = inst, node = node, g = g, context = context, trace = trace)
                                ?: return FireResult.Failed(strictCoverageMessage(node.id))
                    }
                    g.converging -> {
                        inst = consumeConvergence(instance = inst, node = node, g = g, trace = trace)
                        for (edge in spec.outgoing[node.id].orEmpty()) {
                            inst = placeToken(instance = inst, edge = edge, trace = trace)
                        }
                    }
                    else -> {
                        // Neither converging nor diverging — plain pass-through, same as
                        // EXCLUSIVE converging, regardless of `g.family` (see KDoc above).
                        // Routing this through consumeConvergence() would dispatch on
                        // family and, for PARALLEL/INCLUSIVE, emit a spurious
                        // JoinReached trace entry for a gateway that synchronised nothing.
                        inst = inst.withoutTokenAt(node.id)
                        trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                        for (edge in spec.outgoing[node.id].orEmpty()) {
                            inst = placeToken(instance = inst, edge = edge, trace = trace)
                        }
                    }
                }
            }
        }

        return FireResult.Fired(instance = inst, trace = trace)
    }

    private fun strictCoverageMessage(nodeId: String): String =
        "Gateway '$nodeId': no guard matched, no default edge, and strictGuardCoverage=true."

    private fun consumeConvergence(
        instance: ActivityInstance,
        node: TokenFlowNode,
        g: GatewaySpec,
        trace: MutableList<TraceEntry>,
    ): ActivityInstance {
        val clock = instance.clock
        return when (g.family) {
            GatewayFamily.EXCLUSIVE -> {
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                instance.withoutTokenAt(node.id)
            }
            GatewayFamily.PARALLEL -> {
                val incomingEdgeIds = spec.incoming[node.id].orEmpty().map { it.id }
                var updated = instance
                repeat(incomingEdgeIds.size.coerceAtLeast(1)) { updated = updated.withoutTokenAt(node.id) }
                // Consume exactly one arrival per incoming edge — NOT clearJoinArrivals(),
                // which would wipe a surplus arrival (an edge that delivered more than
                // once before its siblings caught up) and strand its matching token
                // forever, since isReady() requires every incoming edge present again.
                updated = updated.consumeJoinArrivals(joinId = node.id, edgeIds = incomingEdgeIds)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                trace +=
                    TraceEntry.JoinReached(
                        seqNo = clock,
                        timestamp = "",
                        nodeId = node.id,
                        awaitingEdgeIds = incomingEdgeIds,
                        isReady = true,
                        clock = clock,
                    )
                updated
            }
            GatewayFamily.INCLUSIVE -> {
                val arrivals = instance.joinEdgeTokens[node.id].orEmpty()
                val totalArrivals = arrivals.values.sum().coerceAtLeast(1)
                var updated = instance
                repeat(totalArrivals) { updated = updated.withoutTokenAt(node.id) }
                updated = updated.clearJoinArrivals(node.id)
                trace += mkTokenConsumed(nodeId = node.id, clock = clock)
                trace +=
                    TraceEntry.JoinReached(
                        seqNo = clock,
                        timestamp = "",
                        nodeId = node.id,
                        awaitingEdgeIds = spec.incoming[node.id].orEmpty().map { it.id },
                        isReady = true,
                        clock = clock,
                    )
                updated
            }
        }
    }

    /** Returns null on a strict-guard-coverage violation; caller turns that into [FireResult.Failed]. */
    private fun diverge(
        instance: ActivityInstance,
        node: TokenFlowNode,
        g: GatewaySpec,
        context: TokenFlowContext,
        trace: MutableList<TraceEntry>,
    ): ActivityInstance? {
        val clock = instance.clock
        val outs = spec.outgoing[node.id].orEmpty()

        return when (g.family) {
            GatewayFamily.PARALLEL -> {
                var updated = instance
                trace +=
                    TraceEntry.ForkSplit(
                        seqNo = clock,
                        timestamp = "",
                        nodeId = node.id,
                        targetNodeIds = outs.map { it.targetNodeId },
                        clock = clock,
                    )
                for (edge in outs) updated = placeToken(instance = updated, edge = edge, trace = trace)
                updated
            }
            GatewayFamily.EXCLUSIVE -> {
                val defaultEdge = defaultEdgeOf(g = g, outs = outs)
                val nonDefault = outs.filter { it.id != defaultEdge?.id }
                val chosen = nonDefault.firstOrNull { evaluateEdgeGuard(edge = it, context = context) } ?: defaultEdge
                when {
                    chosen != null -> {
                        trace +=
                            TraceEntry.DecisionTaken(
                                seqNo = clock,
                                timestamp = "",
                                nodeId = node.id,
                                chosenEdgeId = chosen.id,
                                guard = chosen.guard,
                                clock = clock,
                            )
                        placeToken(instance = instance, edge = chosen, trace = trace)
                    }
                    options.strictGuardCoverage -> null
                    else -> instance
                }
            }
            GatewayFamily.INCLUSIVE -> {
                val defaultEdge = defaultEdgeOf(g = g, outs = outs)
                val nonDefault = outs.filter { it.id != defaultEdge?.id }
                val matched = nonDefault.filter { evaluateEdgeGuard(edge = it, context = context) }
                val chosenEdges = matched.ifEmpty { listOfNotNull(defaultEdge) }
                when {
                    chosenEdges.isNotEmpty() -> {
                        for (edge in chosenEdges) {
                            trace +=
                                TraceEntry.DecisionTaken(
                                    seqNo = clock,
                                    timestamp = "",
                                    nodeId = node.id,
                                    chosenEdgeId = edge.id,
                                    guard = edge.guard,
                                    clock = clock,
                                )
                        }
                        if (chosenEdges.size > 1) {
                            trace +=
                                TraceEntry.ForkSplit(
                                    seqNo = clock,
                                    timestamp = "",
                                    nodeId = node.id,
                                    targetNodeIds = chosenEdges.map { it.targetNodeId },
                                    clock = clock,
                                )
                        }
                        var updated = instance
                        for (edge in chosenEdges) updated = placeToken(instance = updated, edge = edge, trace = trace)
                        updated
                    }
                    options.strictGuardCoverage -> null
                    else -> instance
                }
            }
        }
    }

    /**
     * Resolves the default (fallback) outgoing edge of a diverging gateway.
     *
     * Prefers [GatewaySpec.defaultEdgeId] — the standards-conformant way to
     * express a BPMN 2.0 default flow (`<exclusiveGateway default="sfX"/>`,
     * mapped by `BpmnXmlImporter`/`BpmnGateway.defaultFlow`, and the DSL's
     * `gateway(default = "sfX")`) — and falls back to [TokenFlowEdge.isDefault]
     * (the older, non-standard per-edge flag some adapters/tests still set)
     * only when no edge id matches [GatewaySpec.defaultEdgeId]. Before this,
     * `defaultEdgeId` was parsed and stored but never consulted here, so a
     * standards-conformant `default="sfX"` attribute — which never sets
     * `isDefault` on the edge itself — silently had no effect at runtime: no
     * guard matching meant `chosen == null`, and (with the default
     * `strictGuardCoverage = false`) the token was dropped in place.
     */
    private fun defaultEdgeOf(
        g: GatewaySpec,
        outs: List<TokenFlowEdge>,
    ): TokenFlowEdge? = g.defaultEdgeId?.let { id -> outs.firstOrNull { it.id == id } } ?: outs.firstOrNull { it.isDefault }

    private fun evaluateEdgeGuard(
        edge: TokenFlowEdge,
        context: TokenFlowContext,
    ): Boolean {
        val g = edge.guard
        if (g.isNullOrBlank()) return true
        val syntheticInstance = TokenFlowEvalContext(context.variables)
        val result = guards.evaluate(guard = g, instance = syntheticInstance, event = Event.of("advance"))
        guardResultListener(edge, result)
        return result == GuardResult.True
    }

    private fun placeToken(
        instance: ActivityInstance,
        edge: TokenFlowEdge,
        trace: MutableList<TraceEntry>,
    ): ActivityInstance {
        val targetId = edge.targetNodeId
        val targetNode = spec.nodes[targetId]
        var updated = instance.withTokenAt(targetId)
        trace += mkTokenPlaced(nodeId = targetId, clock = instance.clock)
        if (targetNode?.kind == TokenNodeKind.GATEWAY && targetNode.gateway?.converging == true) {
            updated = updated.withJoinArrival(joinId = targetId, edgeId = edge.id)
        }
        return updated
    }

    private fun applyEmptyMarkingPostcondition(
        instance: ActivityInstance,
        trace: MutableList<TraceEntry>,
    ): ActivityInstance {
        if (instance.tokenCounts.isNotEmpty() || instance.isTerminated) return instance
        val terminated = instance.copy(isTerminated = true)
        trace += TraceEntry.ActivityTerminated(seqNo = terminated.clock, timestamp = "", clock = terminated.clock)
        return terminated
    }

    private fun mkTokenPlaced(
        nodeId: String,
        clock: Long,
    ): TraceEntry.TokenPlaced = TraceEntry.TokenPlaced(seqNo = clock, timestamp = "", nodeId = nodeId, clock = clock)

    private fun mkTokenConsumed(
        nodeId: String,
        clock: Long,
    ): TraceEntry.TokenConsumed = TraceEntry.TokenConsumed(seqNo = clock, timestamp = "", nodeId = nodeId, clock = clock)

    /**
     * Converts [ms] to nanoseconds, clamped instead of allowed to overflow `Long` — see [run]'s
     * comment on the wall-clock deadline. A non-positive [ms] clamps to `0`, matching this
     * engine's fail-closed posture elsewhere (e.g. `SimulateCommand`'s `restrictTo(min = 1)` on
     * `--time-budget-ms`, which stops a non-positive value at CLI-parse time — this clamp is the
     * defence-in-depth backstop for any other caller constructing [TokenFlowLimits] directly).
     */
    private fun safeMillisToNanos(ms: Long): Long = ms.coerceIn(0L, Long.MAX_VALUE / 1_000_000L) * 1_000_000L
}
