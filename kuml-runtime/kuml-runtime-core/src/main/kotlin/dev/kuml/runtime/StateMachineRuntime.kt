package dev.kuml.runtime

import dev.kuml.runtime.internal.RegionInfo
import dev.kuml.runtime.internal.SYNTHETIC_ROOT_ID
import dev.kuml.runtime.internal.allVertices
import dev.kuml.runtime.internal.buildParentOf
import dev.kuml.runtime.internal.lowestCommonAncestor
import dev.kuml.runtime.internal.pathUpTo
import dev.kuml.runtime.internal.triggerName
import dev.kuml.runtime.snapshot.MigrationException
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.runtime.snapshot.StateMachineSnapshot
import dev.kuml.runtime.snapshot.fingerprint
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTimer
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * V1.1.5 — State-Machine-Interpreter nach Operational-Semantics-Regeln 1–9.
 *
 * Construction takes a [GuardEvaluator] strategy — default is
 * [GuardEvaluator.AlwaysTrue] in Ticket 2, will be wired to
 * `OclGuardEvaluator` in Ticket 3.
 */
public class StateMachineRuntime(
    private val guards: GuardEvaluator = OclGuardEvaluator(),
    private val clock: () -> Instant = Instant::now,
    private val effects: EffectInvoker = EffectInvoker.NoOp,
    private val limits: StmRuntimeLimits = StmRuntimeLimits.DEFAULT,
    private val proseGuards: ProseGuardPolicy = ProseGuardPolicy.Block,
    /**
     * When set, the run uses a deterministic virtual clock starting at this instant: trace
     * timestamps come from it and [advance] moves it. `null` keeps the wall [clock].
     */
    private val virtualStart: Instant? = null,
) : BehaviourInterpreter<UmlStateMachine, StateMachineInstance> {
    // ── start ────────────────────────────────────────────────────────────────

    override fun start(model: UmlStateMachine): StateMachineInstance {
        val parentOf = buildParentOf(model)
        val vertexById = allVertices(model).associateBy { it.id }
        val instance =
            StateMachineInstance(
                model = model,
                parentOf = parentOf,
                vertexById = vertexById,
            )

        checkModelLimits(instance)
        if (virtualStart != null) {
            instance.virtualNowMs = virtualStart.toEpochMilli()
            log(
                instance = instance,
                entry =
                    TraceEntry.ClockStarted(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        startTime = now(instance),
                    ),
            )
        }

        // Find the topmost INITIAL pseudostate (one is required).
        val rootInitial =
            model.vertices.firstOrNull {
                it is UmlPseudostate && it.kind == PseudostateKind.INITIAL
            }
                ?: error(
                    "State machine '${model.name}' has no top-level INITIAL pseudostate. " +
                        "V1.1.5 requires exactly one initial vertex per composite level.",
                )
        enterFollowingTransition(instance = instance, fromVertex = rootInitial)
        drainInternal(instance)
        return instance
    }

    private fun checkModelLimits(instance: StateMachineInstance) {
        for ((owner, regions) in instance.regionsByOwner) {
            if (regions.size > limits.maxRegions) {
                throw StmLimitExceededException(limit = "maxRegions", value = regions.size.toLong(), stateId = owner)
            }
        }
        for (v in instance.vertexById.keys) {
            val d = depthOf(instance = instance, id = v)
            if (d > limits.maxDepth) throw StmLimitExceededException(limit = "maxDepth", value = d.toLong(), stateId = v)
        }
    }

    // ── step ─────────────────────────────────────────────────────────────────

    override fun step(
        instance: StateMachineInstance,
        event: Event,
    ): StepResult {
        if (instance.isTerminated) {
            log(
                instance = instance,
                entry = TraceEntry.Stayed(seqNo = nextSeq(instance), timestamp = now(instance), reason = "state machine terminated"),
            )
            return StepResult.Stayed("state machine terminated")
        }
        log(
            instance = instance,
            entry =
                TraceEntry.EventReceived(
                    seqNo = nextSeq(instance),
                    timestamp = now(instance),
                    eventName = event.name,
                    payload = event.payload,
                ),
        )

        // Snapshot for atomic rollback (Rule 5)
        val snapshotVertices = instance.mutCurrentVertices.toList()
        // Deep-copy variables when an EffectInvoker is active; otherwise a shallow
        // copy is sufficient since the NoOp invoker never mutates nested structures.
        val snapshotVariables: Map<String, Any?> =
            if (effects === EffectInvoker.NoOp) {
                instance.variables.toMap()
            } else {
                instance.variables.mapValues { (_, v) -> v.toJsonElement().toKotlinValue() }
            }
        val snapshotQueueSize = instance.mutInternalQueue.size
        val snapshotTraceSize = instance.mutTrace.size
        val snapshotTerminated = instance.isTerminated
        val snapshotHistory = instance.historyMemory.toMap()
        val snapshotTimers = instance.armedTimerList.toList()
        val snapshotTimerSeq = instance.timerSeq
        val snapshotVirtualNow = instance.virtualNowMs

        return try {
            val result = processExternal(instance = instance, event = event)
            drainInternal(instance)
            result
        } catch (ex: Throwable) {
            // Roll back
            instance.mutCurrentVertices.clear()
            instance.mutCurrentVertices.addAll(snapshotVertices)
            instance.variables.clear()
            instance.variables.putAll(snapshotVariables)
            while (instance.mutInternalQueue.size > snapshotQueueSize) instance.mutInternalQueue.removeLast()
            while (instance.mutTrace.size > snapshotTraceSize) instance.mutTrace.removeAt(instance.mutTrace.lastIndex)
            instance.isTerminated = snapshotTerminated
            instance.historyMemory.clear()
            instance.historyMemory.putAll(snapshotHistory)
            instance.armedTimerList.clear()
            instance.armedTimerList.addAll(snapshotTimers)
            instance.timerSeq = snapshotTimerSeq
            instance.virtualNowMs = snapshotVirtualNow
            log(
                instance = instance,
                entry =
                    TraceEntry.ActionError(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        transitionId = null,
                        message = ex.message ?: ex.javaClass.simpleName,
                    ),
            )
            StepResult.Error(ex)
        }
    }

    // ── snapshot / restore ───────────────────────────────────────────────────

    override fun snapshot(instance: StateMachineInstance): Snapshot =
        Snapshot(
            currentVertexIds = instance.mutCurrentVertices.map { it.id },
            variables = instance.variables.mapValues { (_, v) -> v.toJsonElement() },
            traceSeqNo = instance.seqCounter,
        )

    override fun restore(
        model: UmlStateMachine,
        snapshot: Snapshot,
    ): StateMachineInstance {
        val parentOf = buildParentOf(model)
        val vertexById = allVertices(model).associateBy { it.id }
        val instance =
            StateMachineInstance(
                model = model,
                parentOf = parentOf,
                vertexById = vertexById,
            )
        for (id in snapshot.currentVertexIds) {
            val v = vertexById[id] ?: error("Snapshot references unknown vertex id '$id'")
            instance.mutCurrentVertices += v
        }
        instance.variables.putAll(snapshot.variables.mapValues { (_, v) -> v.toKotlinValue() })
        instance.seqCounter = snapshot.traceSeqNo
        return instance
    }

    // ── full snapshot / restore (V2) ─────────────────────────────────────────

    /**
     * Erstellt einen vollständigen [StateMachineSnapshot], der auch die interne
     * Queue, den kompletten Trace, den seqCounter und das isTerminated-Flag enthält.
     * Die einfache [snapshot]-Methode bleibt für Backward-Kompatibilität erhalten.
     */
    public fun snapshotFull(instance: StateMachineInstance): StateMachineSnapshot =
        StateMachineSnapshot(
            modelId = instance.model.id,
            modelFingerprint = fingerprint(instance.model),
            currentVertexIds = instance.mutCurrentVertices.map { it.id },
            variables = instance.variables.mapValues { (_, v) -> v.toJsonElement() },
            internalQueue = instance.mutInternalQueue.toList(),
            trace = instance.mutTrace.toList(),
            seqCounter = instance.seqCounter,
            isTerminated = instance.isTerminated,
            schemaVersion = StateMachineSnapshot.CURRENT_SCHEMA_VERSION,
            historyMemory = instance.historyMemory.toMap(),
            armedTimers = instance.armedTimerList.toList(),
            virtualNowEpochMs = instance.virtualNowMs,
            timerSeq = instance.timerSeq,
        )

    /**
     * Stellt eine [StateMachineInstance] aus einem [StateMachineSnapshot] wieder her.
     * Prüft Kompatibilität via [policy] vor der Wiederherstellung.
     *
     * @throws dev.kuml.runtime.snapshot.MigrationException wenn [policy] den
     *   Snapshot ablehnt.
     */
    public fun restoreFrom(
        model: UmlStateMachine,
        snapshot: StateMachineSnapshot,
        policy: MigrationPolicy = MigrationPolicy.Reject,
    ): StateMachineInstance {
        if (snapshot.schemaVersion > StateMachineSnapshot.CURRENT_SCHEMA_VERSION) {
            throw MigrationException(
                reason =
                    "snapshot schema version ${snapshot.schemaVersion} is newer than the supported version " +
                        "${StateMachineSnapshot.CURRENT_SCHEMA_VERSION}",
                expected = StateMachineSnapshot.CURRENT_SCHEMA_VERSION.toString(),
                actual = snapshot.schemaVersion.toString(),
            )
        }
        val currentFingerprint = fingerprint(model)
        val allVertexList = allVertices(model)
        val currentVertexIds = allVertexList.map { it.id }.toSet()

        policy.check(
            snapshotFingerprint = snapshot.modelFingerprint,
            currentFingerprint = currentFingerprint,
            snapshotVertexIds = snapshot.currentVertexIds,
            currentVertexIds = currentVertexIds,
        )

        val parentOf = buildParentOf(model)
        val vertexById = allVertexList.associateBy { it.id }
        val instance =
            StateMachineInstance(
                model = model,
                parentOf = parentOf,
                vertexById = vertexById,
            )

        for (id in snapshot.currentVertexIds) {
            val v = vertexById[id] ?: error("Snapshot references unknown vertex id '$id'")
            instance.mutCurrentVertices += v
        }
        instance.variables.putAll(snapshot.variables.mapValues { (_, v) -> v.toKotlinValue() })
        instance.mutInternalQueue.addAll(snapshot.internalQueue)
        instance.mutTrace.addAll(snapshot.trace)
        instance.seqCounter = snapshot.seqCounter
        instance.isTerminated = snapshot.isTerminated
        instance.historyMemory.putAll(snapshot.historyMemory)
        instance.armedTimerList.addAll(snapshot.armedTimers)
        instance.timerSeq = snapshot.timerSeq
        instance.virtualNowMs = snapshot.virtualNowEpochMs
        return instance
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    // ── plans (a plan = everything one trigger fires: junction chains, forks, joins) ──

    /**
     * One firing unit: [transitions] in effect order, the vertices left ([sources]) and entered
     * ([targets]), and the [trigger] transition that was enabled by the event.
     */
    private class Plan(
        val transitions: List<UmlTransition>,
        val sources: List<String>,
        val targets: List<String>,
        val trigger: UmlTransition,
    )

    private class PlanAcc {
        val transitions = mutableListOf<UmlTransition>()
        val targets = mutableListOf<String>()
    }

    /** Process a single external event end-to-end (no queue draining). */
    private fun processExternal(
        instance: StateMachineInstance,
        event: Event,
    ): StepResult {
        val enabled =
            instance.mutCurrentVertices
                .asSequence()
                .flatMap { source -> outgoingTransitions(instance = instance, source = source).asSequence() }
                .filter { tr -> triggerMatches(tr = tr, event = event) }
                .filter { tr -> guardOk(instance = instance, tr = tr, event = event) }
                .toList()

        val joinReported = HashSet<String>()
        val plans = enabled.mapNotNull { tr -> resolvePlan(instance = instance, tr = tr, event = event, joinReported = joinReported) }

        if (plans.isEmpty()) {
            log(
                instance = instance,
                entry =
                    TraceEntry.Stayed(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        reason = "no enabled transition for event '${event.name}'",
                    ),
            )
            return StepResult.Stayed("no enabled transition for event '${event.name}'")
        }
        val selected = selectPlans(instance = instance, plans = plans)

        val fired = mutableListOf<Plan>()
        for (plan in selected) {
            if (instance.isTerminated) break
            val stillActive = plan.sources.all { src -> instance.mutCurrentVertices.any { it.id == src } }
            if (!stillActive) {
                fired.firstOrNull()?.let { by -> logPreempted(instance = instance, dropped = plan, by = by) }
                continue
            }
            fireTransition(instance = instance, plan = plan, event = event)
            fired += plan
        }

        return if (instance.isTerminated) {
            StepResult.Terminated
        } else {
            StepResult.Transitioned(
                fromVertexIds = fired.flatMap { it.sources }.distinct(),
                toVertexIds = instance.mutCurrentVertices.map { it.id },
                transitionIds = fired.flatMap { p -> p.transitions.map { it.id } },
            )
        }
    }

    /**
     * Picks the plans that fire for one event: deeper sources win, and a plan is dropped when it
     * would leave a vertex an already chosen plan leaves or sits in. Plans in different orthogonal
     * regions are disjoint and therefore fire together (one per region).
     */
    private fun selectPlans(
        instance: StateMachineInstance,
        plans: List<Plan>,
    ): List<Plan> {
        val ordered = plans.sortedByDescending { depthOf(instance = instance, id = it.trigger.sourceId) }
        val selected = mutableListOf<Plan>()
        val taken = HashMap<String, Plan>()
        for (plan in ordered) {
            val conflict = conflictSet(instance = instance, plan = plan)
            val blocker = conflict.firstNotNullOfOrNull { taken[it] }
            if (blocker != null) {
                if (instance.hasExplicitRegions) logPreempted(instance = instance, dropped = plan, by = blocker)
                continue
            }
            selected += plan
            for (id in conflict) taken[id] = plan
        }
        return selected
    }

    private fun conflictSet(
        instance: StateMachineInstance,
        plan: Plan,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        out += plan.sources
        for (v in verticesToExit(instance = instance, plan = plan)) out += v.id
        return out
    }

    private fun logPreempted(
        instance: StateMachineInstance,
        dropped: Plan,
        by: Plan,
    ) {
        log(
            instance = instance,
            entry =
                TraceEntry.Preempted(
                    seqNo = nextSeq(instance),
                    timestamp = now(instance),
                    transitionId = dropped.trigger.id,
                    byTransitionId = by.trigger.id,
                ),
        )
    }

    private fun planLca(
        instance: StateMachineInstance,
        plan: Plan,
    ): String {
        val ids = plan.sources + plan.targets
        return ids.drop(1).fold(ids.first()) { acc, id -> lowestCommonAncestor(aId = acc, bId = id, parentOf = instance.parentOf) }
    }

    /** Active vertices strictly below the plan's LCA, deepest first. */
    private fun verticesToExit(
        instance: StateMachineInstance,
        plan: Plan,
    ): List<UmlVertex> {
        val lca = planLca(instance = instance, plan = plan)
        return instance.mutCurrentVertices
            .filter { v ->
                var cur: String? = v.id
                while (cur != null && cur != lca) cur = instance.parentOf[cur]
                cur == lca && v.id != lca
            }.sortedByDescending { depthOf(instance = instance, id = it.id) }
    }

    /**
     * Turns an enabled [tr] into a [Plan]: follows junction chains (guards evaluated statically,
     * before anything is exited), expands forks and waits for all sources of a join. Returns `null`
     * when the plan cannot fire (no valid junction path, join still waiting).
     */
    private fun resolvePlan(
        instance: StateMachineInstance,
        tr: UmlTransition,
        event: Event,
        joinReported: MutableSet<String>,
    ): Plan? {
        val target = instance.vertexById[tr.targetId]
        if (target !is UmlPseudostate) return plainPlan(tr)
        return when (target.kind) {
            PseudostateKind.JUNCTION, PseudostateKind.FORK -> {
                val acc = PlanAcc()
                val ok = walkTransition(instance = instance, t = tr, acc = acc, depth = 1, visiting = HashSet(), event = event)
                if (!ok) {
                    val what = if (target.kind == PseudostateKind.FORK) "fork" else "junction"
                    log(
                        instance = instance,
                        entry =
                            TraceEntry.NotFired(
                                seqNo = nextSeq(instance),
                                timestamp = now(instance),
                                transitionId = tr.id,
                                reason = "$what '${target.name.take(80)}': no guard path",
                            ),
                    )
                    null
                } else {
                    Plan(transitions = acc.transitions, sources = listOf(tr.sourceId), targets = acc.targets, trigger = tr)
                }
            }
            PseudostateKind.JOIN -> resolveJoin(instance = instance, tr = tr, join = target, event = event, joinReported = joinReported)
            else -> plainPlan(tr)
        }
    }

    private fun plainPlan(tr: UmlTransition): Plan =
        Plan(transitions = listOf(tr), sources = listOf(tr.sourceId), targets = listOf(tr.targetId), trigger = tr)

    private fun resolveJoin(
        instance: StateMachineInstance,
        tr: UmlTransition,
        join: UmlPseudostate,
        event: Event,
        joinReported: MutableSet<String>,
    ): Plan? {
        val legs = instance.model.transitions.filter { it.targetId == join.id }
        val missing = mutableListOf<String>()
        for (leg in legs) {
            val active = instance.mutCurrentVertices.any { it.id == leg.sourceId }
            val ready =
                active &&
                    (
                        leg === tr ||
                            (
                                // A timer leg is ready only when it is the one fired by its own timer, or when
                                // it fired earlier and has been waiting at the join since (source still active).
                                if (leg.timer == null) {
                                    (leg.trigger.isNullOrBlank() || triggerMatches(tr = leg, event = event)) &&
                                        guardOk(instance = instance, tr = leg, event = event)
                                } else {
                                    timerLegFiredAndWaiting(instance = instance, leg = leg)
                                }
                            )
                    )
            if (!ready) missing += leg.sourceId
        }
        if (missing.isNotEmpty()) {
            if (joinReported.add(join.id)) {
                log(
                    instance = instance,
                    entry =
                        TraceEntry.JoinWaiting(
                            seqNo = nextSeq(instance),
                            timestamp = now(instance),
                            joinId = join.id,
                            missing = missing,
                        ),
                )
            }
            return null
        }
        val out = outgoingTransitions(instance = instance, source = join).firstOrNull() ?: return null
        val acc = PlanAcc()
        acc.transitions += legs
        if (!walkTransition(instance = instance, t = out, acc = acc, depth = 1, visiting = HashSet(), event = event)) return null
        return Plan(transitions = acc.transitions, sources = legs.map { it.sourceId }, targets = acc.targets, trigger = tr)
    }

    /**
     * `true` when the timer of [leg] expired while its join was still waiting and nothing has
     * happened to the source state since: derived from the trace (no extra state to snapshot or roll
     * back). A later entry/exit of the source, or a guard-false expiry, cancels the pending firing.
     */
    private fun timerLegFiredAndWaiting(
        instance: StateMachineInstance,
        leg: UmlTransition,
    ): Boolean {
        val trace = instance.mutTrace
        for (i in trace.indices.reversed()) {
            when (val e = trace[i]) {
                is TraceEntry.TimerFired -> if (e.transitionId == leg.id) return true
                is TraceEntry.StateEntered -> if (e.vertexId == leg.sourceId) return false
                is TraceEntry.StateExited -> if (e.vertexId == leg.sourceId) return false
                is TraceEntry.NotFired -> if (e.transitionId == leg.id) return false
                else -> Unit
            }
        }
        return false
    }

    private fun walkTransition(
        instance: StateMachineInstance,
        t: UmlTransition,
        acc: PlanAcc,
        depth: Int,
        visiting: MutableSet<String>,
        event: Event,
    ): Boolean {
        if (depth > limits.maxJunctionChain) {
            throw StmLimitExceededException(limit = "maxJunctionChain", value = depth.toLong(), stateId = t.targetId)
        }
        acc.transitions += t
        return walkTarget(instance = instance, vertexId = t.targetId, acc = acc, depth = depth, visiting = visiting, event = event)
    }

    private fun walkTarget(
        instance: StateMachineInstance,
        vertexId: String,
        acc: PlanAcc,
        depth: Int,
        visiting: MutableSet<String>,
        event: Event,
    ): Boolean {
        val v = instance.vertexById[vertexId]
        if (v is UmlPseudostate && v.kind == PseudostateKind.JUNCTION) {
            if (!visiting.add(v.id)) return false // cycle through junctions: no path
            val tSize = acc.transitions.size
            val gSize = acc.targets.size
            for (out in outgoingTransitions(instance = instance, source = v)) {
                if (guardOk(instance = instance, tr = out, event = event) &&
                    walkTransition(instance = instance, t = out, acc = acc, depth = depth + 1, visiting = visiting, event = event)
                ) {
                    visiting.remove(v.id)
                    return true
                }
                while (acc.transitions.size > tSize) acc.transitions.removeAt(acc.transitions.lastIndex)
                while (acc.targets.size > gSize) acc.targets.removeAt(acc.targets.lastIndex)
            }
            visiting.remove(v.id)
            return false
        }
        if (v is UmlPseudostate && v.kind == PseudostateKind.FORK) {
            val outs = outgoingTransitions(instance = instance, source = v)
            if (outs.isEmpty()) return false
            for (out in outs) {
                if (!walkTransition(
                        instance = instance,
                        t = out,
                        acc = acc,
                        depth = depth + 1,
                        visiting = visiting,
                        event = event,
                    )
                ) {
                    return false
                }
            }
            return true
        }
        acc.targets += vertexId
        return true
    }

    /** Drain the internal FIFO queue. */
    private fun drainInternal(instance: StateMachineInstance) {
        while (instance.mutInternalQueue.isNotEmpty() && !instance.isTerminated) {
            val ev = instance.mutInternalQueue.removeFirst()
            processExternal(instance = instance, event = ev)
        }
    }

    private fun outgoingTransitions(
        instance: StateMachineInstance,
        source: UmlVertex,
    ): List<UmlTransition> = instance.model.transitions.filter { it.sourceId == source.id }

    private fun triggerMatches(
        tr: UmlTransition,
        event: Event,
    ): Boolean {
        val name = triggerName(tr.trigger) ?: return false
        return name == event.name
    }

    private fun guardOk(
        instance: StateMachineInstance,
        tr: UmlTransition,
        event: Event,
    ): Boolean {
        // UML convention: a null or blank guard is unconditionally true.
        // Short-circuit so user guard evaluators are never asked about null.
        if (tr.guard.isNullOrBlank()) {
            log(
                instance = instance,
                entry =
                    TraceEntry.GuardEvaluated(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        transitionId = tr.id,
                        guard = "(null)",
                        result = true,
                    ),
            )
            return true
        }
        if (tr.guardIsProse) return proseGuardOk(instance = instance, tr = tr)
        val result = guards.evaluate(guard = tr.guard, instance = instance, event = event)
        log(
            instance = instance,
            entry =
                TraceEntry.GuardEvaluated(
                    seqNo = nextSeq(instance),
                    timestamp = now(instance),
                    transitionId = tr.id,
                    guard = tr.guard ?: "(null)",
                    result = result == GuardResult.True,
                ),
        )
        if (result is GuardResult.Failed) {
            log(
                instance = instance,
                entry =
                    TraceEntry.GuardWarning(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        transitionId = tr.id,
                        guard = tr.guard ?: "(null)",
                        message = result.message,
                    ),
            )
        }
        return result == GuardResult.True
    }

    /** Free-text guard: the [ProseGuardPolicy] decides; the runtime never evaluates prose itself. */
    private fun proseGuardOk(
        instance: StateMachineInstance,
        tr: UmlTransition,
    ): Boolean {
        val guardText = (tr.guard ?: "").take(MAX_TRACE_TEXT)
        val policy = proseGuards
        if (policy is ProseGuardPolicy.Block) {
            log(
                instance = instance,
                entry =
                    TraceEntry.GuardNotEvaluable(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        transitionId = tr.id,
                        guard = guardText,
                        message = "prose guard not evaluable",
                    ),
            )
            return false
        }
        val allowed = if (policy is ProseGuardPolicy.Ask) policy.decide(tr) else true
        log(
            instance = instance,
            entry =
                TraceEntry.GuardEvaluated(
                    seqNo = nextSeq(instance),
                    timestamp = now(instance),
                    transitionId = tr.id,
                    guard = guardText,
                    result = allowed,
                ),
        )
        return allowed
    }

    private fun depthOf(
        instance: StateMachineInstance,
        id: String,
    ): Int {
        var d = 0
        var cur = instance.parentOf[id]
        while (cur != null && cur != SYNTHETIC_ROOT_ID) {
            d++
            cur = instance.parentOf[cur]
        }
        return d
    }

    /** Exit Source→LCA, run effects, enter LCA→Targets. Handles CHOICE auto-fire on entry. */
    private fun fireTransition(
        instance: StateMachineInstance,
        plan: Plan,
        event: Event,
    ) {
        val lca = planLca(instance = instance, plan = plan)

        // All currently active vertices that are strict descendants of the LCA are exited bottom-up.
        val descendantsToExit = verticesToExit(instance = instance, plan = plan)
        recordHistory(instance = instance, exiting = descendantsToExit)
        for (v in descendantsToExit) exitVertex(instance = instance, vertexId = v.id, event = event)
        instance.mutCurrentVertices.removeAll(descendantsToExit.toSet())

        for (tr in plan.transitions) {
            // Effect
            if (tr.effect != null) {
                log(
                    instance = instance,
                    entry =
                        TraceEntry.ActionInvoked(
                            seqNo = nextSeq(instance),
                            timestamp = now(instance),
                            phase = ActionPhase.EFFECT,
                            action = tr.effect!!,
                            vertexId = null,
                            transitionId = tr.id,
                        ),
                )
                val outcome =
                    effects.invoke(
                        action = tr.effect!!,
                        phase = ActionPhase.EFFECT,
                        vertexId = null,
                        transitionId = tr.id,
                        instance = instance,
                        event = event,
                    )
                if (outcome is InvocationOutcome.Error) throw EffectInvocationException(message = outcome.message, cause = outcome.cause)
            }
            log(
                instance = instance,
                entry =
                    TraceEntry.TransitionFired(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        transitionId = tr.id,
                        fromVertexId = tr.sourceId,
                        toVertexId = tr.targetId,
                    ),
            )
        }

        // Enter (top-down) from just-below-LCA down to the targets
        enterTargets(instance = instance, targets = plan.targets, stopAt = lca, event = event)
    }

    private fun exitVertex(
        instance: StateMachineInstance,
        vertexId: String,
        event: Event = syntheticEvent(),
    ) {
        val v = instance.vertexById[vertexId] ?: return
        instance.armedTimerList.removeAll { it.sourceId == vertexId }
        if (v is UmlState && v.exit != null) {
            log(
                instance = instance,
                entry =
                    TraceEntry.ActionInvoked(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        phase = ActionPhase.EXIT,
                        action = v.exit!!,
                        vertexId = v.id,
                        transitionId = null,
                    ),
            )
            val outcome =
                effects.invoke(
                    action = v.exit!!,
                    phase = ActionPhase.EXIT,
                    vertexId = v.id,
                    transitionId = null,
                    instance = instance,
                    event = event,
                )
            if (outcome is InvocationOutcome.Error) throw EffectInvocationException(message = outcome.message, cause = outcome.cause)
        }
        log(
            instance = instance,
            entry =
                TraceEntry.StateExited(
                    seqNo = nextSeq(instance),
                    timestamp = now(instance),
                    vertexId = v.id,
                ),
        )
    }

    /**
     * Enters [targets] (top-down along their shared ancestry below [stopAt]). Composite states
     * descend into the initial pseudostate of every region no target path crosses; a history
     * pseudostate target restores its remembered configuration instead (Regel 8 for CHOICE).
     */
    private fun enterTargets(
        instance: StateMachineInstance,
        targets: List<String>,
        stopAt: String,
        event: Event = syntheticEvent(),
    ) {
        val order = LinkedHashSet<String>()
        for (t in targets) order.addAll(pathUpTo(vertexId = t, stopAt = stopAt, parentOf = instance.parentOf).reversed())
        val coveredParents = order.mapNotNullTo(HashSet()) { instance.parentOf[it] }
        for (vid in order) {
            if (instance.isTerminated) return
            val v = instance.vertexById[vid] ?: continue // region node
            if (v is UmlPseudostate && (v.kind == PseudostateKind.SHALLOW_HISTORY || v.kind == PseudostateKind.DEEP_HISTORY)) {
                restoreHistory(instance = instance, history = v, event = event)
                continue
            }
            enterOne(instance = instance, v = v, event = event)
            // A composite state descends into every region that no target path crosses.
            if (v is UmlState) {
                for (region in instance.regionsByOwner[v.id].orEmpty()) {
                    if (region.nodeId in coveredParents) continue
                    fillRegion(instance = instance, owner = v, region = region, event = event)
                    if (instance.isTerminated) return
                }
            }
            if (v is UmlFinalState) {
                // A final state in an explicit (orthogonal) region only completes that region.
                val parent = instance.parentOf[v.id]
                val inExplicitRegion = parent != null && parent != SYNTHETIC_ROOT_ID && instance.vertexById[parent] == null
                if (!inExplicitRegion) {
                    instance.isTerminated = true
                    log(
                        instance = instance,
                        entry =
                            TraceEntry.Terminated(
                                seqNo = nextSeq(instance),
                                timestamp = now(instance),
                                finalVertexId = v.id,
                            ),
                    )
                    return
                }
            }
            if (v is UmlPseudostate) {
                when (v.kind) {
                    // CHOICE (Regel 8) and INITIAL: follow the first guard-enabled outgoing transition.
                    PseudostateKind.CHOICE, PseudostateKind.INITIAL ->
                        enterFollowingTransition(instance = instance, fromVertex = v, event = event)
                    PseudostateKind.FORK, PseudostateKind.JOIN, PseudostateKind.JUNCTION ->
                        error(
                            "Pseudostate kind ${v.kind} on vertex '${v.id}' cannot be entered directly (it is resolved by its incoming transition).",
                        )
                    PseudostateKind.SHALLOW_HISTORY, PseudostateKind.DEEP_HISTORY -> Unit
                }
            }
        }
    }

    /** Adds [v] to the active set, arms its timers and runs entry / do actions. */
    private fun enterOne(
        instance: StateMachineInstance,
        v: UmlVertex,
        event: Event,
    ) {
        instance.mutCurrentVertices += v
        if (v is UmlState) armTimers(instance = instance, state = v)
        if (instance.hasExplicitRegions) checkActiveLeaves(instance = instance, at = v.id)
        if (v is UmlState && v.entry != null) {
            log(
                instance = instance,
                entry =
                    TraceEntry.ActionInvoked(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        phase = ActionPhase.ENTRY,
                        action = v.entry!!,
                        vertexId = v.id,
                        transitionId = null,
                    ),
            )
            val outcome =
                effects.invoke(
                    action = v.entry!!,
                    phase = ActionPhase.ENTRY,
                    vertexId = v.id,
                    transitionId = null,
                    instance = instance,
                    event = event,
                )
            if (outcome is InvocationOutcome.Error) throw EffectInvocationException(message = outcome.message, cause = outcome.cause)
        }
        if (v is UmlState && v.doActivity != null) {
            log(
                instance = instance,
                entry =
                    TraceEntry.ActionInvoked(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        phase = ActionPhase.DO_ACTIVITY,
                        action = v.doActivity!!,
                        vertexId = v.id,
                        transitionId = null,
                    ),
            )
            val outcome2 =
                effects.invoke(
                    action = v.doActivity!!,
                    phase = ActionPhase.DO_ACTIVITY,
                    vertexId = v.id,
                    transitionId = null,
                    instance = instance,
                    event = event,
                )
            if (outcome2 is InvocationOutcome.Error) throw EffectInvocationException(message = outcome2.message, cause = outcome2.cause)
        }
        log(
            instance = instance,
            entry =
                TraceEntry.StateEntered(
                    seqNo = nextSeq(instance),
                    timestamp = now(instance),
                    vertexId = v.id,
                ),
        )
    }

    /** Enters [region] of the freshly entered [owner] through its initial pseudostate. */
    private fun fillRegion(
        instance: StateMachineInstance,
        owner: UmlState,
        region: RegionInfo,
        event: Event,
    ) {
        val initial = region.vertices.firstOrNull { it is UmlPseudostate && it.kind == PseudostateKind.INITIAL }
        if (initial == null) {
            // The classic implicit region may omit its initial pseudostate (composite stays unentered inside);
            // an explicit region must have one.
            if (region.explicit) error("Region '${region.nodeId.take(80)}' of state '${owner.name.take(80)}' has no initial pseudostate.")
            return
        }
        enterFollowingTransition(instance = instance, fromVertex = initial, event = event)
    }

    private fun checkActiveLeaves(
        instance: StateMachineInstance,
        at: String,
    ) {
        val parents = instance.mutCurrentVertices.mapNotNullTo(HashSet()) { instance.parentOf[it.id] }
        val leaves = instance.mutCurrentVertices.count { it.id !in parents }
        if (leaves > limits.maxActiveLeaves) {
            throw StmLimitExceededException(limit = "maxActiveLeaves", value = leaves.toLong(), stateId = at)
        }
    }

    // ── history ──────────────────────────────────────────────────────────────

    /** Remembers, for every history pseudostate of a state that is about to be left, what was active. */
    private fun recordHistory(
        instance: StateMachineInstance,
        exiting: List<UmlVertex>,
    ) {
        for (x in exiting) {
            if (x !is UmlState) continue
            for (region in instance.regionsByOwner[x.id].orEmpty()) {
                val histories =
                    region.vertices.filterIsInstance<UmlPseudostate>().filter {
                        it.kind == PseudostateKind.SHALLOW_HISTORY || it.kind == PseudostateKind.DEEP_HISTORY
                    }
                if (histories.isEmpty()) continue
                val activeParents = instance.mutCurrentVertices.mapNotNullTo(HashSet()) { instance.parentOf[it.id] }
                val childIds =
                    instance.mutCurrentVertices
                        .filter { instance.parentOf[it.id] == region.nodeId && it !is UmlPseudostate }
                        .map { it.id }
                val leafIds =
                    instance.mutCurrentVertices
                        .filter { v ->
                            v !is UmlPseudostate &&
                                v.id !in activeParents &&
                                isBelow(instance = instance, id = v.id, ancestor = region.nodeId)
                        }.map { it.id }
                for (h in histories) {
                    val mem = if (h.kind == PseudostateKind.SHALLOW_HISTORY) childIds else leafIds
                    if (mem.isNotEmpty()) instance.historyMemory[h.id] = mem
                }
            }
        }
    }

    private fun isBelow(
        instance: StateMachineInstance,
        id: String,
        ancestor: String,
    ): Boolean {
        var cur = instance.parentOf[id]
        while (cur != null) {
            if (cur == ancestor) return true
            cur = instance.parentOf[cur]
        }
        return false
    }

    /** Re-enters what [history] remembered; falls back to its default transition, then to the region's initial state. */
    private fun restoreHistory(
        instance: StateMachineInstance,
        history: UmlPseudostate,
        event: Event,
    ) {
        val regionNode = instance.parentOf.getValue(history.id)
        if (regionNode == SYNTHETIC_ROOT_ID) {
            error("History pseudostate '${history.id}' has no parent composite state.")
        }
        val remembered = instance.historyMemory[history.id]
        if (!remembered.isNullOrEmpty()) {
            enterTargets(instance = instance, targets = remembered, stopAt = regionNode, event = event)
            return
        }
        val default = outgoingTransitions(instance = instance, source = history).firstOrNull()
        if (default != null) {
            val plan =
                resolvePlan(instance = instance, tr = default, event = event, joinReported = HashSet())
                    ?: error("History default transition '${default.id}' cannot fire.")
            fireTransition(instance = instance, plan = plan, event = event)
            return
        }
        val region =
            instance.regionsByOwner.values
                .flatten()
                .firstOrNull { it.nodeId == regionNode } ?: return
        val owner = instance.vertexById[region.ownerId] as? UmlState ?: return
        fillRegion(instance = instance, owner = owner, region = region, event = event)
    }

    // ── timers ───────────────────────────────────────────────────────────────

    private fun nowMs(instance: StateMachineInstance): Long = instance.virtualNowMs ?: clock().toEpochMilli()

    private fun dueFor(
        instance: StateMachineInstance,
        timer: UmlTimer,
    ): Long =
        when (timer) {
            is UmlTimer.After -> nowMs(instance) + timer.durationMs
            is UmlTimer.At -> maxOf(timer.epochMs, nowMs(instance))
        }

    private fun armTimers(
        instance: StateMachineInstance,
        state: UmlState,
    ) {
        for (t in instance.model.transitions) {
            val timer = t.timer ?: continue
            if (t.sourceId != state.id) continue
            armTimer(instance = instance, tr = t, timer = timer)
        }
    }

    private fun armTimer(
        instance: StateMachineInstance,
        tr: UmlTransition,
        timer: UmlTimer,
    ) {
        if (instance.armedTimerList.size >= limits.maxArmedTimers) {
            throw StmLimitExceededException(
                limit = "maxArmedTimers",
                value = instance.armedTimerList.size + 1L,
                stateId = tr.sourceId,
            )
        }
        instance.armedTimerList +=
            ArmedTimer(
                transitionId = tr.id,
                sourceId = tr.sourceId,
                dueEpochMs = dueFor(instance = instance, timer = timer),
                seq = instance.timerSeq++,
            )
    }

    /** The armed timers with their absolute due time and the time left, soonest first. */
    public fun armedTimers(instance: StateMachineInstance): List<ArmedTimerView> {
        val now = nowMs(instance)
        return instance.armedTimerList
            .sortedWith(timerOrder(instance))
            .map {
                ArmedTimerView(
                    transitionId = it.transitionId,
                    sourceId = it.sourceId,
                    dueAt = Instant.ofEpochMilli(it.dueEpochMs),
                    remaining = maxOf(0L, it.dueEpochMs - now).milliseconds,
                )
            }
    }

    /** Ids of the active leaf vertices (active vertices without an active child), in entry order. */
    public fun activeLeaves(instance: StateMachineInstance): List<String> {
        val parents = instance.mutCurrentVertices.mapNotNullTo(HashSet()) { instance.parentOf[it.id] }
        return instance.mutCurrentVertices.filter { it.id !in parents }.map { it.id }
    }

    /** What each history pseudostate currently remembers (history id to remembered vertex ids). */
    public fun historyMemory(instance: StateMachineInstance): Map<String, List<String>> = instance.historyMemory.toMap()

    /** The virtual clock of [instance], or `null` when it runs on the wall clock. */
    public fun virtualNow(instance: StateMachineInstance): Instant? = instance.virtualNowMs?.let { Instant.ofEpochMilli(it) }

    /** Due time of the soonest armed timer, or `null` when none is armed. */
    public fun nextTimerDue(instance: StateMachineInstance): Instant? =
        instance.armedTimerList.minWithOrNull(timerOrder(instance))?.let { Instant.ofEpochMilli(it.dueEpochMs) }

    private fun timerOrder(instance: StateMachineInstance): Comparator<ArmedTimer> =
        compareBy<ArmedTimer>(
            { it.dueEpochMs },
            { -depthOf(instance = instance, id = it.sourceId) },
            { tid -> instance.model.transitions.indexOfFirst { it.id == tid.transitionId } },
            { it.seq },
        )

    /**
     * Moves the virtual clock forward by [by] and fires every timer that expires on the way, in
     * due-time order (ties: deeper source, declaration order). Each firing is processed like an
     * event named `timer:<transitionId>`.
     *
     * @throws StmLimitExceededException when [by] exceeds the horizon or more than the allowed number
     *   of timers fire in one call (e.g. a self-loop with a tiny `after`).
     */
    public fun advance(
        instance: StateMachineInstance,
        by: Duration,
    ): AdvanceResult {
        require(!by.isNegative()) { "advance: duration must not be negative" }
        if (by > limits.maxAdvanceHorizon) {
            throw StmLimitExceededException(limit = "maxAdvanceHorizon", value = by.inWholeDays, stateId = null)
        }
        val start = instance.virtualNowMs ?: clock().toEpochMilli().also { instance.virtualNowMs = it }
        return advanceTo(instance = instance, targetMs = start + by.inWholeMilliseconds)
    }

    /** Jumps to the next armed timer (no horizon limit on the jump) and fires it. */
    public fun advanceToNextTimer(instance: StateMachineInstance): AdvanceResult {
        val due = instance.armedTimerList.minWithOrNull(timerOrder(instance))?.dueEpochMs
        val start = instance.virtualNowMs ?: clock().toEpochMilli().also { instance.virtualNowMs = it }
        return advanceTo(instance = instance, targetMs = maxOf(start, due ?: start))
    }

    private fun advanceTo(
        instance: StateMachineInstance,
        targetMs: Long,
    ): AdvanceResult {
        val firedIds = mutableListOf<String>()
        var firings = 0
        while (!instance.isTerminated) {
            val next = instance.armedTimerList.minWithOrNull(timerOrder(instance)) ?: break
            if (next.dueEpochMs > targetMs) break
            firings++
            if (firings > limits.maxFiringsPerAdvance) {
                throw StmLimitExceededException(limit = "maxFiringsPerAdvance", value = firings.toLong(), stateId = next.sourceId)
            }
            instance.virtualNowMs = maxOf(instance.virtualNowMs ?: next.dueEpochMs, next.dueEpochMs)
            instance.armedTimerList.remove(next)
            if (fireTimer(instance = instance, armed = next)) firedIds += next.transitionId
        }
        if (!instance.isTerminated) instance.virtualNowMs = maxOf(instance.virtualNowMs ?: targetMs, targetMs)
        return AdvanceResult(
            firedTransitionIds = firedIds,
            now = Instant.ofEpochMilli(instance.virtualNowMs ?: targetMs),
            terminated = instance.isTerminated,
        )
    }

    /** Fires one expired timer atomically; returns `true` when its transition actually fired. */
    private fun fireTimer(
        instance: StateMachineInstance,
        armed: ArmedTimer,
    ): Boolean {
        val tr = instance.model.transitions.firstOrNull { it.id == armed.transitionId } ?: return false
        val event = Event(name = "timer:${tr.id}")
        val history = instance.historyMemory.toMap()
        val vertices = instance.mutCurrentVertices.toList()
        val timers = instance.armedTimerList.toList()
        val timerSeq = instance.timerSeq
        val variables: Map<String, Any?> = instance.variables.mapValues { (_, v) -> v.toJsonElement().toKotlinValue() }
        val queueSize = instance.mutInternalQueue.size
        val traceSize = instance.mutTrace.size
        val terminated = instance.isTerminated
        return try {
            log(
                instance = instance,
                entry =
                    TraceEntry.TimerFired(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        transitionId = tr.id,
                        dueTime = Instant.ofEpochMilli(armed.dueEpochMs).toString(),
                    ),
            )
            if (!guardOk(instance = instance, tr = tr, event = event)) {
                log(
                    instance = instance,
                    entry =
                        TraceEntry.NotFired(
                            seqNo = nextSeq(instance),
                            timestamp = now(instance),
                            transitionId = tr.id,
                            reason = "guard false at timer expiry",
                        ),
                )
                return false
            }
            val plan = resolvePlan(instance = instance, tr = tr, event = event, joinReported = HashSet()) ?: return false
            if (instance.mutCurrentVertices.none { it.id == tr.sourceId }) return false
            fireTransition(instance = instance, plan = plan, event = event)
            // A self-loop keeps the state active, so the `after` timer is restarted explicitly.
            val timer = tr.timer
            if (timer is UmlTimer.After && tr.sourceId == tr.targetId && instance.mutCurrentVertices.any { it.id == tr.sourceId }) {
                instance.armedTimerList.removeAll { it.transitionId == tr.id }
                armTimer(instance = instance, tr = tr, timer = timer)
            }
            drainInternal(instance)
            true
        } catch (ex: Throwable) {
            instance.mutCurrentVertices.clear()
            instance.mutCurrentVertices.addAll(vertices)
            instance.variables.clear()
            instance.variables.putAll(variables)
            while (instance.mutInternalQueue.size > queueSize) instance.mutInternalQueue.removeLast()
            while (instance.mutTrace.size > traceSize) instance.mutTrace.removeAt(instance.mutTrace.lastIndex)
            instance.isTerminated = terminated
            instance.historyMemory.clear()
            instance.historyMemory.putAll(history)
            instance.armedTimerList.clear()
            instance.armedTimerList.addAll(timers)
            instance.armedTimerList.removeAll { it.transitionId == armed.transitionId && it.seq == armed.seq }
            instance.timerSeq = timerSeq
            // Limit hits are rolled back first and then propagated to the caller.
            if (ex is StmLimitExceededException) throw ex
            log(
                instance = instance,
                entry =
                    TraceEntry.ActionError(
                        seqNo = nextSeq(instance),
                        timestamp = now(instance),
                        transitionId = tr.id,
                        message = ex.message ?: ex.javaClass.simpleName,
                    ),
            )
            false
        }
    }

    /** Used for INITIAL, CHOICE and region fill — follow the first guard-enabled outgoing transition. */
    private fun enterFollowingTransition(
        instance: StateMachineInstance,
        fromVertex: UmlVertex,
        event: Event = syntheticEvent(),
    ) {
        val outs = outgoingTransitions(instance = instance, source = fromVertex)
        val plan =
            outs.firstNotNullOfOrNull { tr ->
                if (guardOk(instance = instance, tr = tr, event = syntheticEvent())) {
                    resolvePlan(instance = instance, tr = tr, event = syntheticEvent(), joinReported = HashSet())
                } else {
                    null
                }
            }
                ?: error(
                    "No enabled transition out of '${fromVertex.id}' " +
                        "(kind=${(fromVertex as? UmlPseudostate)?.kind ?: "STATE"}).",
                )
        fireTransition(instance = instance, plan = plan, event = event)
    }

    private fun syntheticEvent(): Event = Event(name = "")

    private fun log(
        instance: StateMachineInstance,
        entry: TraceEntry,
    ) {
        instance.mutTrace += entry
    }

    private fun nextSeq(instance: StateMachineInstance): Long = instance.seqCounter++

    private fun now(instance: StateMachineInstance): String =
        instance.virtualNowMs?.let { Instant.ofEpochMilli(it).toString() } ?: clock().toString()

    private companion object {
        /** Prose / guard text copied into trace entries is cut to this length. */
        const val MAX_TRACE_TEXT: Int = 512
    }
}

// ── Conversion helpers between Kotlin values and JsonElements ────────────────

internal fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(this.entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
        is List<*> -> JsonArray(this.map { it.toJsonElement() })
        else -> JsonPrimitive(this.toString())
    }

internal fun JsonElement.toKotlinValue(): Any? =
    when (this) {
        is JsonNull -> null
        is JsonPrimitive ->
            when {
                isString -> content
                content == "true" || content == "false" -> content.toBooleanStrict()
                else -> content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
            }
        is JsonObject -> mapValues { (_, v) -> v.toKotlinValue() }
        is JsonArray -> map { it.toKotlinValue() }
    }
