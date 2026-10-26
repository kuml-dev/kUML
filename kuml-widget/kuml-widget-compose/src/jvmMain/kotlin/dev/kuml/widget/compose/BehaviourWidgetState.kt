package dev.kuml.widget.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.runtime.Event
import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.runtimeValueToJson
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.runtime.snapshot.StateMachineSnapshot
import dev.kuml.runtime.toRuntimeValue
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.JsonElement

/**
 * Compose-observable state holder for a running [UmlStateMachine] simulation.
 *
 * Holds the runtime, instance, trace, and scrub position. All mutable fields
 * are backed by [mutableStateOf] so Compose recomposition is triggered automatically.
 *
 * @param initialModel the [UmlStateMachine] to simulate; also the baseline [resetAll] and [editedGuardIds] compare against.
 * @param runtime the [StateMachineRuntime] driving the instance.
 * @param editPolicy editing permissions for the widget.
 */
@Stable
public class BehaviourWidgetState(
    public val initialModel: UmlStateMachine,
    internal val runtime: StateMachineRuntime,
    public val editPolicy: EditPolicy = EditPolicy.None,
) {
    /**
     * The currently simulated [UmlStateMachine]. Observable — a successful guard
     * edit ([changeGuard]) replaces this with a new model instance, which
     * recomposes any layout/SVG derived from it (see [BehaviourWidget]).
     */
    public var model: UmlStateMachine by mutableStateOf(initialModel)
        internal set

    internal var runningInstance: StateMachineInstance = runtime.start(model)
    private var initialSnapshot: StateMachineSnapshot

    /** Full trace of all [dev.kuml.runtime.TraceEntry]s recorded so far. */
    public var trace: List<dev.kuml.runtime.TraceEntry> by mutableStateOf(emptyList())
        private set

    /**
     * Current scrub position in the trace: an index in `[0, trace.size]`.
     * `trace.size` means "live" (not scrubbing).
     */
    public var tracePosition: Int by mutableStateOf(0)
        private set

    /** `true` when the scrub position is behind the live end of the trace. */
    public val isScrubbing: Boolean get() = tracePosition < trace.size

    /**
     * Variables of the live instance as JSON. Observable; refreshed after every step, fork, reset,
     * guard edit and variable edit (also when the variables were changed by an effect).
     */
    public var liveVariables: Map<String, JsonElement> by mutableStateOf(emptyMap())
        private set

    init {
        initialSnapshot = runtime.snapshotFull(runningInstance)
        syncFromInstance()
    }

    /** Variables the simulation started with (the baseline [resetAll] restores). */
    public val initialVariables: Map<String, JsonElement> get() = initialSnapshot.variables

    /**
     * IDs of transitions whose guard currently differs from the guard in [initialModel]
     * (blank and `null` count as the same, "no guard"). Derived, so it can never drift.
     */
    public val editedGuardIds: Set<String>
        get() {
            val original = initialModel.transitions.associate { it.id to it.guard?.ifBlank { null } }
            return model.transitions
                .filter { original.containsKey(it.id) && original[it.id] != it.guard?.ifBlank { null } }
                .mapTo(LinkedHashSet()) { it.id }
        }

    /** The guard of [transitionId] as written in the original model, or `null` when it had none (or is unknown). */
    public fun originalGuard(transitionId: String): String? = initialModel.transitions.firstOrNull { it.id == transitionId }?.guard

    /**
     * The initial set of active vertex IDs (captured right after `runtime.start`).
     *
     * Used as the seed for [HighlightHelpers.replayActiveVertices].
     */
    private val initialActiveVertexIds: Set<String>
        get() = initialSnapshot.currentVertexIds.toSet()

    /**
     * Returns the set of vertex IDs that are highlighted (active) at the current
     * [tracePosition].
     */
    public fun currentHighlightIds(): Set<String> =
        HighlightHelpers.replayActiveVertices(
            initialActive = initialActiveVertexIds,
            trace = trace,
            upToExclusive = tracePosition,
        )

    /**
     * Sends an event to the state machine.
     *
     * If currently scrubbing ([isScrubbing] is `true`), the instance is first
     * forked at the current scrub position before the event is dispatched so
     * the trace branches correctly from the scrub point.
     *
     * @param eventName the event name.
     * @param payloadJson JSON object delivered as `event.<key>` to guards; validated by
     *   [EventPayloads.parse] BEFORE anything is forked or changed.
     * @return the [StepResult] of the dispatched event — V3.x (Desktop live simulation) needs
     *   this to distinguish `Stayed`/`Terminated`/`Error` outcomes from a normal transition
     *   without re-deriving it from the trace.
     * @throws InvalidEventPayloadException when [payloadJson] is invalid; the state is untouched.
     */
    public fun sendEvent(
        eventName: String,
        payloadJson: String = "{}",
    ): StepResult {
        val payload =
            when (val parsed = EventPayloads.parse(payloadJson)) {
                is PayloadParseResult.Valid -> parsed.payload
                is PayloadParseResult.Invalid -> throw InvalidEventPayloadException(parsed)
            }
        if (isScrubbing) {
            forkAtScrubPosition()
        }
        val event = Event(name = eventName, payload = payload)
        val result = runtime.step(instance = runningInstance, event = event)
        syncFromInstance()
        return result
    }

    /**
     * Moves the scrub position to [position], clamped to `[0, trace.size]`.
     */
    public fun scrubTo(position: Int) {
        tracePosition = position.coerceIn(0, trace.size)
    }

    /**
     * Resets the simulation to the initial snapshot.
     *
     * Restores the instance to the state captured in [initialSnapshot], clears
     * the trace, and moves the scrub position to 0.
     */
    public fun reset() {
        runningInstance = runtime.restoreFrom(model = model, snapshot = initialSnapshot, policy = MigrationPolicy.Reject)
        syncFromInstance()
    }

    /**
     * Full reset: like [reset], but also restores the ORIGINAL model, i.e. every guard edit is
     * undone (and the initial variables come back). [reset] keeps edited guards.
     */
    public fun resetAll() {
        model = initialModel
        runningInstance = runtime.restoreFrom(model = initialModel, snapshot = initialSnapshot, policy = MigrationPolicy.Reject)
        syncFromInstance()
    }

    /**
     * Sets variable [name] of the live instance to [value] (same JSON to value mapping as
     * snapshot restore). Rejected while scrubbing, for names outside `[A-Za-z_][A-Za-z0-9_]{0,63}`
     * and when it would create a 65th variable.
     */
    public fun setVariable(
        name: String,
        value: JsonElement,
    ): VariableEditOutcome {
        if (isScrubbing) return VariableEditOutcome.RejectedScrubbing
        if (!VARIABLE_NAME.matches(name)) return VariableEditOutcome.InvalidName
        if (name !in runningInstance.variables && runningInstance.variables.size >= MAX_VARIABLES) return VariableEditOutcome.TooMany
        runningInstance.variables[name] = value.toRuntimeValue()
        syncFromInstance()
        return VariableEditOutcome.Applied
    }

    /** Removes variable [name] from the live instance (a missing variable is a no-op that still reports [VariableEditOutcome.Applied]). */
    public fun removeVariable(name: String): VariableEditOutcome {
        if (isScrubbing) return VariableEditOutcome.RejectedScrubbing
        runningInstance.variables.remove(name)
        syncFromInstance()
        return VariableEditOutcome.Applied
    }

    /**
     * Forks the simulation at the current scrub position.
     *
     * Restores the instance to a snapshot reconstructed at [tracePosition] so that
     * new events diverge from that point rather than continuing from the live end.
     *
     * @param variables when non-null, the forked instance starts with exactly these variables
     *   (a caller that tracks variable history passes the values as of the scrub position);
     *   `null` keeps the initial snapshot's variables, the original behaviour.
     */
    public fun forkAtScrubPosition(variables: Map<String, JsonElement>? = null) {
        val partialTrace = trace.subList(0, tracePosition)
        val activeIds =
            HighlightHelpers.replayActiveVertices(
                initialActive = initialActiveVertexIds,
                trace = trace,
                upToExclusive = tracePosition,
            )
        val forkedSnapshot =
            initialSnapshot.copy(
                currentVertexIds = activeIds.toList(),
                trace = partialTrace,
                seqCounter = if (partialTrace.isEmpty()) 0L else partialTrace.last().seqNo + 1,
                internalQueue = emptyList(),
                isTerminated = false,
                variables = variables ?: initialSnapshot.variables,
            )
        runningInstance = runtime.restoreFrom(model = model, snapshot = forkedSnapshot, policy = MigrationPolicy.Reject)
        syncFromInstance()
    }

    /**
     * Re-derives [trace]/[tracePosition] from the current [runningInstance] and moves
     * the scrub position back to live. Used after an in-place instance swap
     * (e.g. [changeGuard]) where the swap itself doesn't go through
     * [sendEvent]/[reset]/[forkAtScrubPosition].
     */
    internal fun syncTrace() = syncFromInstance()

    /** Re-derives [trace], [tracePosition] (live) and [liveVariables] from [runningInstance]. */
    internal fun syncFromInstance() {
        trace = runningInstance.trace.toList()
        tracePosition = trace.size
        liveVariables = runningInstance.variables.mapValues { (_, v) -> runtimeValueToJson(v) }
    }

    private companion object {
        val VARIABLE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        const val MAX_VARIABLES = 64
    }
}

/** Outcome of [BehaviourWidgetState.setVariable] / [BehaviourWidgetState.removeVariable]. */
public sealed interface VariableEditOutcome {
    /** The edit was applied. */
    public data object Applied : VariableEditOutcome

    /** Variables are read-only while the history is being scrubbed. */
    public data object RejectedScrubbing : VariableEditOutcome

    /** The name does not match `[A-Za-z_][A-Za-z0-9_]{0,63}`. */
    public data object InvalidName : VariableEditOutcome

    /** The instance already holds the maximum of 64 variables. */
    public data object TooMany : VariableEditOutcome
}
