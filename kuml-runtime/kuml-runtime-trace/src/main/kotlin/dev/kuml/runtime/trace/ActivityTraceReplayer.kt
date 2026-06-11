package dev.kuml.runtime.trace

import dev.kuml.runtime.TraceDiff
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.runtime.activity.ActivityRuntime

public class ActivityTraceReplayer {
    public fun replay(
        runtime: ActivityRuntime,
        original: TraceFile,
        eventContext: Map<String, Any> = emptyMap(),
        maxSteps: Int = 1000,
        failOnDeadlock: Boolean = true,
        modelId: String? = null,
    ): ActivityReplayReport {
        // Flavour check — reject STM traces
        val flavour = TraceFlavourDetector.detect(original)
        if (flavour == TraceFlavour.STM || flavour == TraceFlavour.MIXED) {
            throw UnsupportedTraceFlavourException(
                "ActivityTraceReplayer does not support STM-flavoured traces (flavour=$flavour). " +
                    "Use TraceReplayer for STM traces.",
            )
        }

        // Model ID check
        val traceModelId = original.modelId
        if (traceModelId != null && modelId != null && traceModelId != modelId) {
            throw IllegalArgumentException(
                "Model ID mismatch: trace was recorded for model '$traceModelId' " +
                    "but the provided activity has ID '$modelId'.",
            )
        }

        // Run — MUST call start() explicitly then run(initial=...) to get startTrace
        val (initInstance, startTrace) = runtime.start(eventContext)
        val (finalInstance, runTrace) =
            runtime.run(
                initial = initInstance,
                eventContext = eventContext,
                maxSteps = maxSteps,
                failOnDeadlock = failOnDeadlock,
            )
        val actualTrace: List<TraceEntry> = startTrace + runTrace

        val diff = TraceDiff.compare(actual = actualTrace, expected = original.entries)

        return ActivityReplayReport(
            isMatch = diff.isMatch,
            originalSize = original.entries.size,
            actualSize = actualTrace.size,
            actualTrace = actualTrace,
            diff = diff,
            eventContext = eventContext,
            maxSteps = maxSteps,
            finalClock = finalInstance.clock,
        )
    }
}

public data class ActivityReplayReport(
    public val isMatch: Boolean,
    public val originalSize: Int,
    public val actualSize: Int,
    public val actualTrace: List<TraceEntry>,
    public val diff: TraceDiff.Report,
    public val eventContext: Map<String, Any>,
    public val maxSteps: Int,
    public val finalClock: Long,
) {
    public fun toHumanReadable(verbose: Boolean = false): String {
        val sb = StringBuilder()
        if (isMatch) {
            sb.appendLine(
                "Activity-replay match: $actualSize entries identical to original " +
                    "$originalSize entries (finalClock=$finalClock).",
            )
        } else {
            sb.appendLine(
                "Activity-replay mismatch: actual=$actualSize entries, original=$originalSize entries, " +
                    "${diff.mismatches.size} mismatch(es) (finalClock=$finalClock).",
            )
        }
        if (verbose) {
            if (eventContext.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Event context:")
                eventContext.toSortedMap().forEach { (k, v) -> sb.appendLine("  $k = $v") }
            }
            if (!isMatch) {
                sb.appendLine()
                sb.append(diff.toHumanReadable())
            }
        }
        return sb.toString().trimEnd()
    }
}
