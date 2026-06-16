package dev.kuml.runtime.trace

import dev.kuml.runtime.AiTraceEntry
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile

public enum class TraceFlavour { STM, ACTIVITY, AI, EMPTY, MIXED }

public object TraceFlavourDetector {
    public fun detect(traceFile: TraceFile): TraceFlavour = detect(traceFile.entries)

    public fun detect(entries: List<TraceEntry>): TraceFlavour {
        if (entries.isEmpty()) return TraceFlavour.EMPTY
        val hasStm = entries.any { isStmEntry(it) }
        val hasAct = entries.any { isActivityEntry(it) }
        val hasAi = entries.any { it is AiTraceEntry }
        return when {
            hasStm && hasAct -> TraceFlavour.MIXED
            hasStm -> TraceFlavour.STM
            hasAct -> TraceFlavour.ACTIVITY
            hasAi -> TraceFlavour.AI
            else -> TraceFlavour.EMPTY
        }
    }

    private fun isStmEntry(e: TraceEntry): Boolean =
        e is TraceEntry.EventReceived ||
            e is TraceEntry.StateEntered ||
            e is TraceEntry.StateExited ||
            e is TraceEntry.ActionInvoked ||
            e is TraceEntry.TransitionFired ||
            e is TraceEntry.GuardEvaluated ||
            e is TraceEntry.GuardWarning ||
            e is TraceEntry.ActionError ||
            e is TraceEntry.Stayed ||
            e is TraceEntry.Terminated

    private fun isActivityEntry(e: TraceEntry): Boolean =
        e is TraceEntry.TokenPlaced ||
            e is TraceEntry.TokenConsumed ||
            e is TraceEntry.DecisionTaken ||
            e is TraceEntry.ForkSplit ||
            e is TraceEntry.JoinReached ||
            e is TraceEntry.ActivityActionInvoked ||
            e is TraceEntry.FlowFinalConsumed ||
            e is TraceEntry.ActivityTerminated
}
