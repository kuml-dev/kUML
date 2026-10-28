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
import kotlin.time.Duration.Companion.milliseconds

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
        // Guard: only STM-flavoured traces are supported
        val flavour = TraceFlavourDetector.detect(original)
        if (flavour == TraceFlavour.ACTIVITY || flavour == TraceFlavour.MIXED || flavour == TraceFlavour.EMPTY) {
            throw UnsupportedTraceFlavourException(
                "TraceReplayer only supports STM-flavoured traces (detected: $flavour). " +
                    "Use ActivityTraceReplayer for Activity traces.",
            )
        }

        // Guard: model ID mismatch
        val traceModelId = original.modelId
        val smModelId = model.id
        if (traceModelId != null && traceModelId != smModelId) {
            throw IllegalArgumentException(
                "Model ID mismatch: trace was recorded for model '$traceModelId' " +
                    "but the provided state machine has ID '$smModelId'.",
            )
        }

        // Deterministic clock: each call returns the next millisecond from epoch 0
        val clock = AtomicLong(0L)
        val clockFn: () -> Instant = { Instant.ofEpochMilli(clock.getAndIncrement()) }

        // Timer-driven traces (wave A2) start with a ClockStarted entry: replay on the same virtual
        // clock and re-advance time wherever the original recorded an expired timer.
        val virtualStart =
            original.entries
                .filterIsInstance<TraceEntry.ClockStarted>()
                .firstOrNull()
                ?.let { Instant.parse(it.startTime) }
        val runtime = StateMachineRuntime(guards = guards, clock = clockFn, virtualStart = virtualStart)
        val instance = runtime.start(model)

        val events = EventsFromTrace.extract(original)
        if (virtualStart == null) {
            for (event in events) {
                if (instance.isTerminated) break
                runtime.step(instance = instance, event = event)
            }
        } else {
            for (entry in original.entries) {
                if (instance.isTerminated) break
                when (entry) {
                    is TraceEntry.EventReceived -> {
                        // Time advances that fired no timer leave no TimerFired entry; the event's
                        // virtual timestamp restores them so later timers are armed at the right time.
                        val at = runCatching { Instant.parse(entry.timestamp) }.getOrNull()
                        val now = runtime.virtualNow(instance)
                        if (at != null && now != null && at.toEpochMilli() > now.toEpochMilli()) {
                            runtime.advance(instance = instance, by = (at.toEpochMilli() - now.toEpochMilli()).milliseconds)
                        }
                        if (instance.isTerminated) continue
                        runtime.step(instance = instance, event = Event(name = entry.eventName, payload = entry.payload))
                    }
                    is TraceEntry.TimerFired -> {
                        val now = runtime.virtualNow(instance) ?: virtualStart
                        val due = Instant.parse(entry.dueTime)
                        val by = (due.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0L)
                        runtime.advance(instance = instance, by = by.milliseconds)
                    }
                    else -> Unit
                }
            }
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
