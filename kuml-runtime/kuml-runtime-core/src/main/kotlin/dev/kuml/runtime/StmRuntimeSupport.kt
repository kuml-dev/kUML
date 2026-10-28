package dev.kuml.runtime

import dev.kuml.uml.UmlTransition
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Hard limits of the state-machine runtime (wave A2). Exceeding one aborts the run with a
 * [StmLimitExceededException]; nothing is ever silently clipped.
 */
public data class StmRuntimeLimits(
    val maxArmedTimers: Int = 1024,
    val maxFiringsPerAdvance: Int = 10_000,
    val maxAdvanceHorizon: Duration = 3650.days,
    val maxRegions: Int = 16,
    val maxDepth: Int = 16,
    val maxActiveLeaves: Int = 64,
    val maxJunctionChain: Int = 32,
) {
    public companion object {
        public val DEFAULT: StmRuntimeLimits = StmRuntimeLimits()
    }
}

/** A runtime limit of [StmRuntimeLimits] was exceeded. */
public class StmLimitExceededException(
    public val limit: String,
    public val value: Long,
    public val stateId: String?,
) : IllegalStateException(
        "State machine limit '$limit' exceeded (value $value)" + (stateId?.let { " at '${it.take(80)}'" } ?: ""),
    )

/** What the runtime does with a prose (free-text) guard it cannot evaluate. */
public sealed interface ProseGuardPolicy {
    /** The guard counts as false; a `GuardNotEvaluable` trace entry records it. */
    public data object Block : ProseGuardPolicy

    /** The guard counts as true. */
    public data object Allow : ProseGuardPolicy

    /** [decide] answers per transition (e.g. a dialog); it is called synchronously. */
    public class Ask(
        public val decide: (UmlTransition) -> Boolean,
    ) : ProseGuardPolicy
}

/** A timer armed for [transitionId] (a transition leaving the active state [sourceId]). */
@Serializable
public data class ArmedTimer(
    val transitionId: String,
    val sourceId: String,
    val dueEpochMs: Long,
    val seq: Long,
)

/** Read-only view of an [ArmedTimer] for UIs. */
public data class ArmedTimerView(
    val transitionId: String,
    val sourceId: String,
    val dueAt: Instant,
    val remaining: Duration,
)

/** Result of [StateMachineRuntime.advance]. */
public data class AdvanceResult(
    val firedTransitionIds: List<String>,
    val now: Instant,
    val terminated: Boolean,
)

/** A virtual-time event: jump to the next armed timer or advance by a fixed duration. */
public sealed interface TimeStep {
    /** `+next` */
    public data object Next : TimeStep

    /** `+5s`, `+1m30s`, ... */
    public data class By(
        val duration: Duration,
    ) : TimeStep
}

/**
 * Syntax of virtual-time events that CLI, MCP and the REPL accept in place of an event name:
 * `+next` or `+<duration>` (`+5s`, `+250ms`, `+1m30s`, `+2h`).
 */
public object TimeEvents {
    /** Longest accepted time-event string. */
    public const val MAX_LENGTH: Int = 64

    /** `true` when [name] is written as a time event (starts with `+`). */
    public fun isTimeEvent(name: String): Boolean = name.startsWith("+")

    /** @throws IllegalArgumentException for malformed, non-positive or overlong input. */
    public fun parse(name: String): TimeStep {
        require(name.length <= MAX_LENGTH) { "time event longer than $MAX_LENGTH characters" }
        if (name == "+next") return TimeStep.Next
        val text = name.removePrefix("+")
        val d =
            try {
                Duration.parse(text)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("invalid time event '${name.take(32)}': use +next or +<duration>, e.g. +5s, +250ms, +1m30s")
            }
        require(d.isFinite() && d.isPositive()) { "time event '${name.take(32)}' must be a positive finite duration" }
        return TimeStep.By(d)
    }
}

/** Applies a time event (`+next` / `+5s`) to [instance]. */
public fun StateMachineRuntime.applyTimeEvent(
    instance: StateMachineInstance,
    name: String,
): AdvanceResult =
    when (val step = TimeEvents.parse(name)) {
        TimeStep.Next -> advanceToNextTimer(instance)
        is TimeStep.By -> advance(instance = instance, by = step.duration)
    }
