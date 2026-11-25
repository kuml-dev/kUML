package dev.kuml.layout.bridge.label

import io.github.oshai.kotlinlogging.KotlinLogging

private val reportLogger = KotlinLogging.logger {}

/**
 * PAL Welle N: reports `KUML-LAYOUT-W013` (an STM label without a free position) at most once per label. One render plans the
 * labels several times (a retry with narrower wraps, the preplan for the notes), and every run used to log the same warning;
 * the planner is now called with `reportUnresolved = false` and the final plan goes through one instance of this class.
 * The set of reported ids is bounded by the number of labels of the diagram.
 */
public class StmUnresolvedReport(
    private val sink: (String) -> Unit = { message -> reportLogger.warn { message } },
) {
    private val seen = HashSet<String>()

    /** Reports every label of [result] that is unresolved and not reported before. */
    public fun report(result: Sysml2LabelPlanResult) {
        for (id in result.unresolved) {
            if (!seen.add(id)) continue
            sink(
                Sysml2DiagramLabelPlan.noFreeSpotMessage(
                    id = id,
                    why = result.unresolvedReason[id] ?: "none",
                    who = result.unresolvedBlockers[id].orEmpty(),
                ),
            )
        }
    }
}
