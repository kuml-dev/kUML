package dev.kuml.runtime.trace

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile

public object ActivityContextFromTrace {
    public data class DecisionRecord(
        public val nodeId: String,
        public val chosenEdgeId: String,
        public val guard: String?,
        public val clock: Long,
    )

    public data class Report(
        public val decisions: List<DecisionRecord>,
        public val terminated: Boolean,
        public val finalClock: Long?,
    ) {
        public fun toHumanReadable(): String {
            val sb = StringBuilder()
            sb.appendLine("Decision path from original trace (${decisions.size} decisions):")
            for (d in decisions) {
                val g = d.guard?.let { "guard='$it'" } ?: "<no guard>"
                sb.appendLine("  [clock=${d.clock}] ${d.nodeId} → ${d.chosenEdgeId}  $g")
            }
            sb.appendLine(
                if (terminated) {
                    "Original reached Final (clock=$finalClock)."
                } else {
                    "Original did NOT terminate."
                },
            )
            return sb.toString().trimEnd()
        }
    }

    public fun extract(traceFile: TraceFile): Report = extract(traceFile.entries)

    public fun extract(entries: List<TraceEntry>): Report {
        val sorted = entries.sortedBy { it.seqNo }
        val decisions =
            sorted.filterIsInstance<TraceEntry.DecisionTaken>().map {
                DecisionRecord(it.nodeId, it.chosenEdgeId, it.guard, it.clock)
            }
        val terminated = sorted.any { it is TraceEntry.ActivityTerminated }
        val finalClock = sorted.filterIsInstance<TraceEntry.ActivityTerminated>().lastOrNull()?.clock
        return Report(decisions, terminated, finalClock)
    }
}
