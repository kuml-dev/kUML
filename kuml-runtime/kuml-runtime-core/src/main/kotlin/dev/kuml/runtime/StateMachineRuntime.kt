package dev.kuml.runtime

import dev.kuml.runtime.internal.SYNTHETIC_ROOT_ID
import dev.kuml.runtime.internal.allVertices
import dev.kuml.runtime.internal.buildParentOf
import dev.kuml.runtime.internal.lowestCommonAncestor
import dev.kuml.runtime.internal.pathUpTo
import dev.kuml.runtime.internal.triggerName
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.runtime.snapshot.StateMachineSnapshot
import dev.kuml.runtime.snapshot.fingerprint
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

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

        // Find the topmost INITIAL pseudostate (one is required).
        val rootInitial =
            model.vertices.firstOrNull {
                it is UmlPseudostate && it.kind == PseudostateKind.INITIAL
            }
                ?: error(
                    "State machine '${model.name}' has no top-level INITIAL pseudostate. " +
                        "V1.1.5 requires exactly one initial vertex per composite level.",
                )
        enterFollowingTransition(instance, fromVertex = rootInitial)
        drainInternal(instance)
        return instance
    }

    // ── step ─────────────────────────────────────────────────────────────────

    override fun step(
        instance: StateMachineInstance,
        event: Event,
    ): StepResult {
        if (instance.isTerminated) {
            log(instance, TraceEntry.Stayed(nextSeq(instance), now(), reason = "state machine terminated"))
            return StepResult.Stayed("state machine terminated")
        }
        log(instance, TraceEntry.EventReceived(nextSeq(instance), now(), event.name, event.payload))

        // Snapshot for atomic rollback (Rule 5)
        val snapshotVertices = instance.mutCurrentVertices.toList()
        val snapshotVariables = instance.variables.toMap()
        val snapshotQueueSize = instance.mutInternalQueue.size
        val snapshotTraceSize = instance.mutTrace.size
        val snapshotTerminated = instance.isTerminated

        return try {
            val result = processExternal(instance, event)
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
            log(
                instance,
                TraceEntry.ActionError(
                    seqNo = nextSeq(instance),
                    timestamp = now(),
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
        return instance
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    /** Process a single external event end-to-end (no queue draining). */
    private fun processExternal(
        instance: StateMachineInstance,
        event: Event,
    ): StepResult {
        val enabled =
            instance.mutCurrentVertices
                .asSequence()
                .flatMap { source -> outgoingTransitions(instance, source).asSequence() }
                .filter { tr -> triggerMatches(tr, event) }
                .filter { tr -> guardOk(instance, tr, event) }
                .toList()

        if (enabled.isEmpty()) {
            log(
                instance,
                TraceEntry.Stayed(
                    seqNo = nextSeq(instance),
                    timestamp = now(),
                    reason = "no enabled transition for event '${event.name}'",
                ),
            )
            return StepResult.Stayed("no enabled transition for event '${event.name}'")
        }
        val chosen = pickDeepest(instance, enabled)
        fireTransition(instance, chosen, event)

        return if (instance.isTerminated) {
            StepResult.Terminated
        } else {
            StepResult.Transitioned(
                fromVertexIds = listOf(chosen.sourceId),
                toVertexIds = instance.mutCurrentVertices.map { it.id },
                transitionIds = listOf(chosen.id),
            )
        }
    }

    /** Drain the internal FIFO queue. */
    private fun drainInternal(instance: StateMachineInstance) {
        while (instance.mutInternalQueue.isNotEmpty() && !instance.isTerminated) {
            val ev = instance.mutInternalQueue.removeFirst()
            processExternal(instance, ev)
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
                instance,
                TraceEntry.GuardEvaluated(
                    seqNo = nextSeq(instance),
                    timestamp = now(),
                    transitionId = tr.id,
                    guard = "(null)",
                    result = true,
                ),
            )
            return true
        }
        val result = guards.evaluate(tr.guard, instance, event)
        log(
            instance,
            TraceEntry.GuardEvaluated(
                seqNo = nextSeq(instance),
                timestamp = now(),
                transitionId = tr.id,
                guard = tr.guard ?: "(null)",
                result = result == GuardResult.True,
            ),
        )
        if (result is GuardResult.Failed) {
            log(
                instance,
                TraceEntry.GuardWarning(
                    seqNo = nextSeq(instance),
                    timestamp = now(),
                    transitionId = tr.id,
                    guard = tr.guard ?: "(null)",
                    message = result.message,
                ),
            )
        }
        return result == GuardResult.True
    }

    /** Returns the transition whose source has the greatest hierarchy depth (definition order ties). */
    private fun pickDeepest(
        instance: StateMachineInstance,
        candidates: List<UmlTransition>,
    ): UmlTransition {
        val depthOf: (String) -> Int = { vertexId ->
            var d = 0
            var cur = instance.parentOf[vertexId]
            while (cur != null && cur != SYNTHETIC_ROOT_ID) {
                d++
                cur = instance.parentOf[cur]
            }
            d
        }
        val maxDepth = candidates.maxOf { depthOf(it.sourceId) }
        return candidates.first { depthOf(it.sourceId) == maxDepth }
    }

    /** Exit Source→LCA, run effect, enter LCA→Target. Handles CHOICE auto-fire on entry. */
    private fun fireTransition(
        instance: StateMachineInstance,
        tr: UmlTransition,
        @Suppress("UNUSED_PARAMETER") event: Event,
    ) {
        val source = instance.vertexById.getValue(tr.sourceId)
        val target = instance.vertexById.getValue(tr.targetId)
        val lca = lowestCommonAncestor(source.id, target.id, instance.parentOf)

        // Determine all currently active vertices that are strict descendants of the LCA.
        // These must all be exited bottom-up.
        val depthOf: (String) -> Int = { vid ->
            var d = 0
            var cur = instance.parentOf[vid]
            while (cur != null && cur != SYNTHETIC_ROOT_ID) {
                d++
                cur = instance.parentOf[cur]
            }
            d
        }
        val descendantsToExit =
            instance.mutCurrentVertices
                .filter { v ->
                    var cur: String? = v.id
                    while (cur != null && cur != lca) cur = instance.parentOf[cur]
                    cur == lca && v.id != lca
                }.sortedByDescending { depthOf(it.id) }
        for (v in descendantsToExit) exitVertex(instance, v.id)
        instance.mutCurrentVertices.removeAll(descendantsToExit.toSet())

        // Effect
        if (tr.effect != null) {
            log(
                instance,
                TraceEntry.ActionInvoked(
                    seqNo = nextSeq(instance),
                    timestamp = now(),
                    phase = ActionPhase.EFFECT,
                    action = tr.effect!!,
                    vertexId = null,
                    transitionId = tr.id,
                ),
            )
        }
        log(
            instance,
            TraceEntry.TransitionFired(
                seqNo = nextSeq(instance),
                timestamp = now(),
                transitionId = tr.id,
                fromVertexId = tr.sourceId,
                toVertexId = tr.targetId,
            ),
        )

        // Enter (top-down) from just-below-LCA down to target
        enterVertex(instance, target.id, lca)
    }

    private fun exitVertex(
        instance: StateMachineInstance,
        vertexId: String,
    ) {
        val v = instance.vertexById[vertexId] ?: return
        if (v is UmlState && v.exit != null) {
            log(
                instance,
                TraceEntry.ActionInvoked(
                    seqNo = nextSeq(instance),
                    timestamp = now(),
                    phase = ActionPhase.EXIT,
                    action = v.exit!!,
                    vertexId = v.id,
                    transitionId = null,
                ),
            )
        }
        log(
            instance,
            TraceEntry.StateExited(
                seqNo = nextSeq(instance),
                timestamp = now(),
                vertexId = v.id,
            ),
        )
    }

    /**
     * Enter [targetId] from above [stopAt] down. Recursively handles CHOICE (Regel 8)
     * by following the first guard-enabled outgoing transition.
     */
    private fun enterVertex(
        instance: StateMachineInstance,
        targetId: String,
        stopAt: String,
    ) {
        val path = pathUpTo(targetId, stopAt, instance.parentOf).reversed()
        for (vid in path) {
            val v = instance.vertexById.getValue(vid)
            instance.mutCurrentVertices += v
            if (v is UmlState && v.entry != null) {
                log(
                    instance,
                    TraceEntry.ActionInvoked(
                        seqNo = nextSeq(instance),
                        timestamp = now(),
                        phase = ActionPhase.ENTRY,
                        action = v.entry!!,
                        vertexId = v.id,
                        transitionId = null,
                    ),
                )
            }
            if (v is UmlState && v.doActivity != null) {
                log(
                    instance,
                    TraceEntry.ActionInvoked(
                        seqNo = nextSeq(instance),
                        timestamp = now(),
                        phase = ActionPhase.DO_ACTIVITY,
                        action = v.doActivity!!,
                        vertexId = v.id,
                        transitionId = null,
                    ),
                )
            }
            log(
                instance,
                TraceEntry.StateEntered(
                    seqNo = nextSeq(instance),
                    timestamp = now(),
                    vertexId = v.id,
                ),
            )
            // If this is the final hop and the target is a composite state, descend
            // into its internal INITIAL pseudostate to follow UML semantics.
            if (v is UmlState && v.id == targetId && v.substates.isNotEmpty()) {
                val subInitial =
                    v.substates.firstOrNull {
                        it is UmlPseudostate && it.kind == PseudostateKind.INITIAL
                    }
                if (subInitial != null) {
                    enterFollowingTransition(instance, fromVertex = subInitial)
                    return
                }
            }
            if (v is UmlFinalState) {
                instance.isTerminated = true
                log(
                    instance,
                    TraceEntry.Terminated(
                        seqNo = nextSeq(instance),
                        timestamp = now(),
                        finalVertexId = v.id,
                    ),
                )
                return
            }
            if (v is UmlPseudostate) {
                when (v.kind) {
                    PseudostateKind.CHOICE -> {
                        enterFollowingTransition(instance, fromVertex = v)
                        return
                    }
                    PseudostateKind.SHALLOW_HISTORY, PseudostateKind.DEEP_HISTORY ->
                        error(
                            "History pseudostate '${v.id}' is out of scope for V1.1.5 (see ADR-0007 / V2 roadmap).",
                        )
                    PseudostateKind.FORK, PseudostateKind.JOIN, PseudostateKind.JUNCTION ->
                        error(
                            "Pseudostate kind ${v.kind} on vertex '${v.id}' is out of scope for V1.1.5.",
                        )
                    PseudostateKind.INITIAL -> {
                        // Initial is normally only reached at start(); but if used inside a composite,
                        // follow its only outgoing transition (recursion is fine).
                        enterFollowingTransition(instance, fromVertex = v)
                        return
                    }
                }
            }
        }
    }

    /** Used for INITIAL and CHOICE — follow the first guard-enabled outgoing transition. */
    private fun enterFollowingTransition(
        instance: StateMachineInstance,
        fromVertex: UmlVertex,
    ) {
        val outs = outgoingTransitions(instance, fromVertex)
        val next =
            outs.firstOrNull { tr -> guardOk(instance, tr, syntheticEvent()) }
                ?: error(
                    "No enabled transition out of '${fromVertex.id}' " +
                        "(kind=${(fromVertex as? UmlPseudostate)?.kind ?: "STATE"}).",
                )
        fireTransition(instance, next, syntheticEvent())
    }

    private fun syntheticEvent(): Event = Event(name = "")

    private fun log(
        instance: StateMachineInstance,
        entry: TraceEntry,
    ) {
        instance.mutTrace += entry
    }

    private fun nextSeq(instance: StateMachineInstance): Long = instance.seqCounter++

    private fun now(): String = clock().toString()
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
