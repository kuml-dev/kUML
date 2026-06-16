package dev.kuml.runtime

/**
 * Index-basierter Trace-Diff. Timestamps werden beim Vergleich ignoriert
 * (siehe [withoutTimestamp]).
 */
public object TraceDiff {
    public data class Report(
        public val matched: Int,
        public val mismatches: List<Mismatch>,
    ) {
        public val isMatch: Boolean get() = mismatches.isEmpty()

        public fun toHumanReadable(): String {
            if (isMatch) return "Trace match: $matched entries identical."
            val sb = StringBuilder()
            sb.appendLine("Diff: actual vs expected (${mismatches.size} mismatches)")
            for (m in mismatches) {
                sb.appendLine(m.toLines())
                sb.appendLine()
            }
            return sb.toString()
        }
    }

    public sealed interface Mismatch {
        public fun toLines(): String

        public data class ValueDiffer(
            public val index: Int,
            public val expected: TraceEntry,
            public val actual: TraceEntry,
        ) : Mismatch {
            override fun toLines(): String = "- [$index] expected: ${expected.shortDescr()}\n+ [$index] actual:   ${actual.shortDescr()}"
        }

        public data class ExtraActual(
            public val index: Int,
            public val actual: TraceEntry,
        ) : Mismatch {
            override fun toLines(): String = "+ [$index] actual:   ${actual.shortDescr()}  (no expected entry)"
        }

        public data class MissingExpected(
            public val index: Int,
            public val expected: TraceEntry,
        ) : Mismatch {
            override fun toLines(): String = "- [$index] expected: ${expected.shortDescr()}  (no actual entry)"
        }
    }

    public fun compare(
        actual: List<TraceEntry>,
        expected: List<TraceEntry>,
    ): Report {
        val mismatches = mutableListOf<Mismatch>()
        var matched = 0
        val maxLen = maxOf(actual.size, expected.size)
        for (i in 0 until maxLen) {
            val a = actual.getOrNull(i)
            val e = expected.getOrNull(i)
            when {
                a != null && e != null ->
                    if (a.withoutTimestamp() == e.withoutTimestamp()) {
                        matched++
                    } else {
                        mismatches += Mismatch.ValueDiffer(i, e, a)
                    }
                a != null -> mismatches += Mismatch.ExtraActual(i, a)
                e != null -> mismatches += Mismatch.MissingExpected(i, e)
            }
        }
        return Report(matched, mismatches)
    }
}

/** Entfernt Timestamps für stabile Vergleiche. */
internal fun TraceEntry.withoutTimestamp(): TraceEntry =
    when (this) {
        is TraceEntry.EventReceived -> copy(timestamp = "")
        is TraceEntry.StateEntered -> copy(timestamp = "")
        is TraceEntry.StateExited -> copy(timestamp = "")
        is TraceEntry.ActionInvoked -> copy(timestamp = "")
        is TraceEntry.TransitionFired -> copy(timestamp = "")
        is TraceEntry.GuardEvaluated -> copy(timestamp = "")
        is TraceEntry.GuardWarning -> copy(timestamp = "")
        is TraceEntry.ActionError -> copy(timestamp = "")
        is TraceEntry.Stayed -> copy(timestamp = "")
        is TraceEntry.Terminated -> copy(timestamp = "")
        // Activity-Runtime entries (V2.0.18) — timestamps are already empty strings
        is TraceEntry.TokenPlaced -> copy(timestamp = "")
        is TraceEntry.TokenConsumed -> copy(timestamp = "")
        is TraceEntry.DecisionTaken -> copy(timestamp = "")
        is TraceEntry.ForkSplit -> copy(timestamp = "")
        is TraceEntry.JoinReached -> copy(timestamp = "")
        is TraceEntry.ActivityActionInvoked -> copy(timestamp = "")
        is TraceEntry.FlowFinalConsumed -> copy(timestamp = "")
        is TraceEntry.ActivityTerminated -> copy(timestamp = "")
        // AI-lifecycle entries (V3.0.25) — timestamp stripping for stable diffs
        is AiTraceEntry.SessionStarted -> copy(timestamp = "")
        is AiTraceEntry.Validated -> copy(timestamp = "")
        is AiTraceEntry.Applied -> copy(timestamp = "")
        is AiTraceEntry.Rejected -> copy(timestamp = "")
        is AiTraceEntry.SessionAborted -> copy(timestamp = "")
    }

/** Kurze textuelle Beschreibung eines TraceEntry für Diff-Reports. */
internal fun TraceEntry.shortDescr(): String =
    when (this) {
        is TraceEntry.EventReceived -> "EventReceived(name=$eventName)"
        is TraceEntry.StateEntered -> "StateEntered($vertexId)"
        is TraceEntry.StateExited -> "StateExited($vertexId)"
        is TraceEntry.ActionInvoked -> "ActionInvoked(phase=$phase, action='$action')"
        is TraceEntry.TransitionFired -> "TransitionFired(t=$transitionId, $fromVertexId→$toVertexId)"
        is TraceEntry.GuardEvaluated -> "GuardEvaluated(t=$transitionId, guard='$guard', result=$result)"
        is TraceEntry.GuardWarning -> "GuardWarning(t=$transitionId, guard='$guard', msg='$message')"
        is TraceEntry.ActionError -> "ActionError(t=$transitionId, msg='$message')"
        is TraceEntry.Stayed -> "Stayed(reason='$reason')"
        is TraceEntry.Terminated -> "Terminated($finalVertexId)"
        // Activity-Runtime entries (V2.0.18)
        is TraceEntry.TokenPlaced -> "TokenPlaced($nodeId, clock=$clock)"
        is TraceEntry.TokenConsumed -> "TokenConsumed($nodeId, clock=$clock)"
        is TraceEntry.DecisionTaken -> "DecisionTaken($nodeId, edge=$chosenEdgeId, guard=$guard)"
        is TraceEntry.ForkSplit -> "ForkSplit($nodeId, targets=$targetNodeIds)"
        is TraceEntry.JoinReached -> "JoinReached($nodeId, ready=$isReady)"
        is TraceEntry.ActivityActionInvoked -> "ActivityActionInvoked($nodeId, body='$body')"
        is TraceEntry.FlowFinalConsumed -> "FlowFinalConsumed($nodeId)"
        is TraceEntry.ActivityTerminated -> "ActivityTerminated(clock=$clock)"
        // AI-lifecycle entries (V3.0.25)
        is AiTraceEntry.SessionStarted -> "AiSessionStarted(session=$sessionId)"
        is AiTraceEntry.Validated -> "AiValidated(patch=$patchId, phase=$phase, errors=$errorCount)"
        is AiTraceEntry.Applied -> "AiApplied(patch=$patchId, element=$elementId)"
        is AiTraceEntry.Rejected -> "AiRejected(patch=$patchId, reason=$reason)"
        is AiTraceEntry.SessionAborted -> "AiSessionAborted(session=$sessionId, rejected=${rejectedPatchIds.size})"
    }
