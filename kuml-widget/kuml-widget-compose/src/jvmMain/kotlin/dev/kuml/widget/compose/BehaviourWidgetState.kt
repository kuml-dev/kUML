package dev.kuml.widget.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.runtime.Event
import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.runtime.snapshot.StateMachineSnapshot
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.JsonObject

/**
 * Compose-observable state holder for a running [UmlStateMachine] simulation.
 *
 * Holds the runtime, instance, trace, and scrub position. All mutable fields
 * are backed by [mutableStateOf] so Compose recomposition is triggered automatically.
 *
 * @param initialModel the [UmlStateMachine] to simulate.
 * @param runtime the [StateMachineRuntime] driving the instance.
 * @param editPolicy editing permissions for the widget.
 */
@Stable
public class BehaviourWidgetState(
    initialModel: UmlStateMachine,
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

    internal var _instance: StateMachineInstance = runtime.start(model)
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

    init {
        initialSnapshot = runtime.snapshotFull(_instance)
        trace = _instance.trace.toList()
        tracePosition = trace.size
    }

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
     * @param payloadJson optional JSON payload string (informational, not parsed in MVP).
     */
    public fun sendEvent(
        eventName: String,
        @Suppress("UNUSED_PARAMETER") payloadJson: String = "{}",
    ) {
        if (isScrubbing) {
            forkAtScrubPosition()
        }
        val event = Event(
            name = eventName,
            payload = JsonObject(emptyMap()),
        )
        runtime.step(_instance, event)
        trace = _instance.trace.toList()
        tracePosition = trace.size
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
        _instance = runtime.restoreFrom(model, initialSnapshot, MigrationPolicy.Reject)
        trace = _instance.trace.toList()
        tracePosition = trace.size
    }

    /**
     * Forks the simulation at the current scrub position.
     *
     * Restores the instance to a snapshot reconstructed at [tracePosition] so that
     * new events diverge from that point rather than continuing from the live end.
     */
    public fun forkAtScrubPosition() {
        val partialTrace = trace.subList(0, tracePosition)
        val activeIds = HighlightHelpers.replayActiveVertices(
            initialActive = initialActiveVertexIds,
            trace = trace,
            upToExclusive = tracePosition,
        )
        val forkedSnapshot = initialSnapshot.copy(
            currentVertexIds = activeIds.toList(),
            trace = partialTrace,
            seqCounter = if (partialTrace.isEmpty()) 0L else partialTrace.last().seqNo + 1,
            internalQueue = emptyList(),
            isTerminated = false,
        )
        _instance = runtime.restoreFrom(model, forkedSnapshot, MigrationPolicy.Reject)
        trace = _instance.trace.toList()
        tracePosition = trace.size
    }

    /**
     * Re-derives [trace]/[tracePosition] from the current [_instance] and moves
     * the scrub position back to live. Used after an in-place instance swap
     * (e.g. [changeGuard]) where the swap itself doesn't go through
     * [sendEvent]/[reset]/[forkAtScrubPosition].
     */
    internal fun syncTrace() {
        trace = _instance.trace.toList()
        tracePosition = trace.size
    }
}
