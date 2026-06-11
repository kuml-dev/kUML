package dev.kuml.widget.compose

import dev.kuml.runtime.TraceEntry

internal object HighlightHelpers {
    /**
     * Replays [trace] entries up to (but excluding) [upToExclusive] to determine
     * which vertex IDs are active at that position.
     *
     * @param initialActive the set of active vertex IDs before any trace replay
     *   (typically the vertex IDs active right after [StateMachineRuntime.start]).
     * @param trace the full trace list from the state machine instance.
     * @param upToExclusive position in the trace up to which replay should run.
     *   Clamped to `[0, trace.size]`.
     * @return the set of active vertex IDs at the requested trace position.
     */
    fun replayActiveVertices(
        initialActive: Set<String>,
        trace: List<TraceEntry>,
        upToExclusive: Int,
    ): Set<String> {
        val active = initialActive.toMutableSet()
        val end = upToExclusive.coerceAtMost(trace.size)
        for (i in 0 until end) {
            when (val e = trace[i]) {
                is TraceEntry.StateEntered -> active += e.vertexId
                is TraceEntry.StateExited -> active -= e.vertexId
                else -> Unit
            }
        }
        return active
    }
}
