package dev.kuml.runtime.trace

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.TraceDiff
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlStateMachine
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Re-executes a [UmlStateMachine] against the event sequence extracted from an
 * original [TraceFile] and diffs the resulting trace against the original.
 *
 * Only STM traces are supported. Activity-flavoured traces (those containing
 * [TraceEntry.TokenPlaced] etc.) cause an [UnsupportedTraceFlavourException].
 *
 * @param guards Guard evaluator to use during replay (default: [OclGuardEvaluator]).
 */
public class TraceReplayer(
    private val guards: GuardEvaluator = OclGuardEvaluator(),
) {
    /**
     * Replay [original] against [model] and return a [ReplayReport].
     *
     * @throws UnsupportedTraceFlavourException if [original] contains Activity-runtime entries.
     * @throws IllegalArgumentException if both [original].modelId and [model].id are non-null
     *   and differ from each other.
     */
    public fun replay(
        model: UmlStateMachine,
        original: TraceFile,
    ): ReplayReport {
        // Guard: activity traces are not supported
        val hasActivityEntries =
            original.entries.any { entry ->
                entry is TraceEntry.TokenPlaced ||
                    entry is TraceEntry.TokenConsumed ||
                    entry is TraceEntry.DecisionTaken ||
                    entry is TraceEntry.ForkSplit ||
                    entry is TraceEntry.JoinReached ||
                    entry is TraceEntry.ActivityActionInvoked ||
                    entry is TraceEntry.FlowFinalConsumed ||
                    entry is TraceEntry.ActivityTerminated
            }
        if (hasActivityEntries) {
            throw UnsupportedTraceFlavourException(
                "TraceReplayer does not support Activity-flavoured traces. " +
                    "The trace contains Activity-runtime entries (TokenPlaced, TokenConsumed, etc.). " +
                    "Only STM traces can be replayed.",
            )
        }

        // Guard: model ID mismatch
        val traceModelId = original.modelId
        val smModelId = model.id
        if (traceModelId != null && smModelId != null && traceModelId != smModelId) {
            throw IllegalArgumentException(
                "Model ID mismatch: trace was recorded for model '$traceModelId' " +
                    "but the provided state machine has ID '$smModelId'.",
            )
        }

        // Deterministic clock: each call returns the next millisecond from epoch 0
        val clock = AtomicLong(0L)
        val clockFn: () -> Instant = { Instant.ofEpochMilli(clock.getAndIncrement()) }

        val runtime = StateMachineRuntime(guards = guards, clock = clockFn)
        val instance = runtime.start(model)

        val events = EventsFromTrace.extract(original)
        for (event in events) {
            if (instance.isTerminated) break
            runtime.step(instance, event)
        }

        val actualTrace = instance.trace
        val diff = TraceDiff.compare(actual = actualTrace, expected = original.entries)

        return ReplayReport(
            isMatch = diff.isMatch,
            originalSize = original.entries.size,
            actualSize = actualTrace.size,
            events = events,
            actualTrace = actualTrace,
            diff = diff,
        )
    }
}

/**
 * Result of a trace replay operation.
 *
 * @property isMatch True if the replayed trace matches the original exactly
 *   (ignoring timestamps).
 * @property originalSize Number of entries in the original trace.
 * @property actualSize Number of entries produced by the replay.
 * @property events The event sequence extracted from the original and fed into the replay.
 * @property actualTrace The full trace produced by the replay runtime.
 * @property diff Detailed diff report between actual and original traces.
 */
public data class ReplayReport(
    public val isMatch: Boolean,
    public val originalSize: Int,
    public val actualSize: Int,
    public val events: List<Event>,
    public val actualTrace: List<TraceEntry>,
    public val diff: TraceDiff.Report,
) {
    /** Human-readable summary. Set [verbose] to true to include full diff details. */
    public fun toHumanReadable(verbose: Boolean = false): String {
        val sb = StringBuilder()
        if (isMatch) {
            sb.appendLine("Replay match: $actualSize entries identical to original $originalSize entries.")
        } else {
            sb.appendLine(
                "Replay mismatch: actual=$actualSize entries, original=$originalSize entries, " +
                    "${diff.mismatches.size} mismatch(es).",
            )
            if (verbose) {
                sb.appendLine()
                sb.append(diff.toHumanReadable())
            }
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Thrown when [TraceReplayer.replay] is called with an Activity-flavoured trace.
 * The replayer only supports STM (state machine) traces.
 */
public class UnsupportedTraceFlavourException(
    message: String,
) : RuntimeException(message)
