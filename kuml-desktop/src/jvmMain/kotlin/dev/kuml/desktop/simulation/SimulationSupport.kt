package dev.kuml.desktop.simulation

import dev.kuml.core.model.DiagramType
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlVertex

// V3.x — Live-Simulation von Zustandsautomaten im Editor.
//
// Pure, Compose-free helper functions for [SimulationSession] / [SimulationBar]. Kept separate
// so they are unit-testable without a Compose runtime — `kuml-desktop` has no Compose UI test
// harness (see [dev.kuml.desktop.MainWindowRenderTriggerTest] and this module's other
// pure-function extractions for the same reasoning).

/** `true` only for UML [DiagramType.STATE] and SysML-2 [StmDiagram] — the two simulatable kinds. */
internal fun isSimulatable(extracted: ExtractedDiagram): Boolean =
    when (extracted) {
        is ExtractedDiagram.Uml -> extracted.diagram.type == DiagramType.STATE
        is ExtractedDiagram.Sysml2 -> extracted.diagram is StmDiagram
        else -> false
    }

/**
 * Extracts the trigger's event name from a raw UML trigger expression (e.g. `"confirm()"` →
 * `"confirm"`). Mirrors `dev.kuml.runtime.internal.triggerName` — that function is `internal` to
 * `kuml-runtime-core` and not visible here, so the (intentionally tiny) logic is duplicated
 * rather than widening that module's public API for one call site.
 */
private fun triggerEventName(triggerRaw: String?): String? = triggerRaw?.substringBefore('(')?.trim()?.takeIf { it.isNotBlank() }

/**
 * All event names that occur anywhere in [model], deduplicated, in STABLE order of first
 * occurrence in `model.transitions` — the list must never re-sort itself as the simulation
 * steps (Spez. E20), so callers can rely on a fixed left-to-right button layout.
 */
internal fun allEventNames(model: UmlStateMachine): List<String> {
    val seen = LinkedHashSet<String>()
    for (transition in model.transitions) {
        triggerEventName(transition.trigger)?.let { seen += it }
    }
    return seen.toList()
}

/**
 * Recursively collects [activeVertexIds] plus every composite-state ANCESTOR of each active
 * vertex (via [UmlState.substates]) — a transition declared on a composite state is reachable
 * from any of its currently active substates.
 */
internal fun withAncestors(
    model: UmlStateMachine,
    activeVertexIds: Set<String>,
): Set<String> {
    val parentOf = mutableMapOf<String, String>()

    fun index(
        vertices: List<UmlVertex>,
        parentId: String?,
    ) {
        for (v in vertices) {
            if (parentId != null) parentOf[v.id] = parentId
            if (v is UmlState && v.substates.isNotEmpty()) index(v.substates, v.id)
        }
    }
    index(model.vertices, null)

    val result = mutableSetOf<String>()
    for (id in activeVertexIds) {
        var cur: String? = id
        while (cur != null) {
            result += cur
            cur = parentOf[cur]
        }
    }
    return result
}

/**
 * Subset of [allEventNames] that COULD offer a transition in the current state: the trigger's
 * source vertex is active, or a composite-state ancestor of an active vertex (see
 * [withAncestors]). Deliberately NOT a guard evaluation (Spez. E20) — an "enabled" event can
 * still resolve to [StepResult.Stayed] / a guard failure once actually sent; this is an
 * affordance heuristic, not a promise.
 */
internal fun enabledEventNames(
    model: UmlStateMachine,
    activeVertexIds: Set<String>,
): Set<String> {
    if (activeVertexIds.isEmpty()) return emptySet()
    val reachableSources = withAncestors(model = model, activeVertexIds = activeVertexIds)
    val enabled = mutableSetOf<String>()
    for (transition in model.transitions) {
        if (transition.sourceId !in reachableSources) continue
        triggerEventName(transition.trigger)?.let { enabled += it }
    }
    return enabled
}

/** Why [autoAdvanceDecision] stopped an auto-advance run. */
internal enum class AutoAdvanceStopReason { NO_EVENT, AMBIGUOUS, STAYED, GUARD_FAILED, ERROR, TERMINATED, STALE }

/** Result of [autoAdvanceDecision]: either keep going with one specific event, or stop. */
internal sealed interface AutoAdvanceDecision {
    data class Continue(
        val eventName: String,
    ) : AutoAdvanceDecision

    data class Stop(
        val reason: AutoAdvanceStopReason,
    ) : AutoAdvanceDecision
}

/**
 * Decides whether "Automatisch weiter" should fire another event.
 *
 * Continues ONLY when the previous step was clean (not [StepResult.Stayed] / `GuardFailed` /
 * `Error`, and the machine isn't [terminated]) AND exactly one event is currently enabled — a
 * deterministic run driven purely by the model, never a clock or speculative guess among
 * several candidates (Spez. E19: "Determinismus, keine Uhr").
 *
 * Checked FIRST, ahead of everything else: [stale] — the editor's script has diverged from the
 * running session's [dev.kuml.desktop.simulation.SimulationSession.modelSource]. Spez. D18's
 * "veraltet"-banner tells the user the simulation reflects a model that no longer exists; a loop
 * that kept firing events against it regardless would contradict that banner on every tick (see
 * `SimulationBar.kt`'s `LaunchedEffect`, which re-evaluates [stale] on every tick rather than
 * capturing it once).
 */
internal fun autoAdvanceDecision(
    enabledEvents: Set<String>,
    lastResult: StepResult?,
    terminated: Boolean,
    stale: Boolean = false,
): AutoAdvanceDecision {
    if (stale) return AutoAdvanceDecision.Stop(AutoAdvanceStopReason.STALE)
    if (terminated) return AutoAdvanceDecision.Stop(AutoAdvanceStopReason.TERMINATED)
    when (lastResult) {
        is StepResult.Stayed -> return AutoAdvanceDecision.Stop(AutoAdvanceStopReason.STAYED)
        is StepResult.GuardFailed -> return AutoAdvanceDecision.Stop(AutoAdvanceStopReason.GUARD_FAILED)
        is StepResult.Error -> return AutoAdvanceDecision.Stop(AutoAdvanceStopReason.ERROR)
        is StepResult.Terminated -> return AutoAdvanceDecision.Stop(AutoAdvanceStopReason.TERMINATED)
        is StepResult.Transitioned, null -> Unit
    }
    return when (enabledEvents.size) {
        0 -> AutoAdvanceDecision.Stop(AutoAdvanceStopReason.NO_EVENT)
        1 -> AutoAdvanceDecision.Continue(enabledEvents.single())
        else -> AutoAdvanceDecision.Stop(AutoAdvanceStopReason.AMBIGUOUS)
    }
}

/**
 * `true` when [currentScript] no longer matches the script the running [SimulationSession] was
 * started from — the "veraltet" banner's trigger (Spez. D18).
 *
 * Compares full source text, not a hash: a `String.hashCode()` collision (practically
 * vanishingly unlikely, but not cryptographically ruled out) would let a stale simulation masquerade
 * as current — a trust break Spez. D18 exists specifically to prevent. A kUML script is at most a
 * few kilobytes, so holding the full string instead of an `Int` costs nothing that matters.
 */
internal fun isStale(
    sessionSource: String,
    currentScript: String,
): Boolean = sessionSource != currentScript

/** Which highlight-ring IDs must be flipped visible/hidden to go from [previous] to [current]. */
internal data class HighlightDiff(
    val show: Set<String>,
    val hide: Set<String>,
)

internal fun highlightDiff(
    previous: Set<String>,
    current: Set<String>,
): HighlightDiff = HighlightDiff(show = current - previous, hide = previous - current)

/**
 * [TraceEntry.GuardWarning] entries appended to [trace] at or after index [beforeSize] — the
 * delta produced by exactly one [dev.kuml.widget.compose.BehaviourWidgetState.sendEvent] call.
 * `StateMachineRuntime` already logs a `GuardWarning` whenever a guard evaluates to
 * [dev.kuml.runtime.GuardResult.Failed] (including sandbox timeouts) — the desktop only needs to
 * read this trace delta, no runtime change required (Spez. F29).
 */
internal fun newGuardWarnings(
    trace: List<TraceEntry>,
    beforeSize: Int,
): List<TraceEntry.GuardWarning> = trace.drop(beforeSize).filterIsInstance<TraceEntry.GuardWarning>()

/** 500 ms tick used by "Automatisch weiter" (Spez. E19) — deliberately fixed, no speed control. */
internal const val AUTO_ADVANCE_INTERVAL_MS: Long = 500L
