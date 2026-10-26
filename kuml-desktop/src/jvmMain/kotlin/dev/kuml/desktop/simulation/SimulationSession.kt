package dev.kuml.desktop.simulation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.desktop.render.DesktopRenderPipeline
import dev.kuml.desktop.render.SimulationPrepareResult
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.sandbox.EffectExecutor
import dev.kuml.runtime.sandbox.SandboxEffectInvoker
import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator
import dev.kuml.uml.UmlStateMachine
import dev.kuml.widget.compose.BehaviourWidgetState
import dev.kuml.widget.compose.EditPolicy
import dev.kuml.widget.compose.EventPayloads
import dev.kuml.widget.compose.InvalidEventPayloadException
import dev.kuml.widget.compose.PatchOutcome
import dev.kuml.widget.compose.PayloadParseResult
import dev.kuml.widget.compose.VariableEditOutcome
import dev.kuml.widget.compose.changeGuard
import dev.kuml.widget.compose.revertGuard
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Outcome of the last [SimulationSession.send] call, or an ambient session condition — drives
 * the status-bar line (see `MainWindow.kt`'s `StatusBar`).
 */
internal sealed interface SimulationStatus {
    data object Idle : SimulationStatus

    data class Stayed(
        val reason: String,
    ) : SimulationStatus

    data class GuardFailed(
        val transitionId: String,
        val message: String,
    ) : SimulationStatus

    /** Red — a guard was aborted by the sandbox timeout (still logged as a `GuardWarning`). */
    data class GuardTimeout(
        val transitionId: String,
        val message: String,
    ) : SimulationStatus

    /** Red — the runtime threw during a step (rolled back; the instance itself is unaffected). */
    data class Error(
        val message: String,
    ) : SimulationStatus

    data object Terminated : SimulationStatus

    data class AutoStopped(
        val reason: AutoAdvanceStopReason,
    ) : SimulationStatus

    /** Sending an event while scrubbing forked the trace and discarded [count] later steps. */
    data class DiscardedSteps(
        val count: Int,
    ) : SimulationStatus

    /** The event payload was rejected before anything was sent (nothing changed). */
    data class InvalidPayload(
        val result: PayloadParseResult.Invalid,
    ) : SimulationStatus
}

/** Result of [SimulationSession.start]. */
internal sealed interface SimulationStartResult {
    data class Started(
        val session: SimulationSession,
    ) : SimulationStartResult

    data class Unsupported(
        val message: String,
    ) : SimulationStartResult

    data class Failed(
        val message: String,
    ) : SimulationStartResult
}

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Owns one sandboxed [BehaviourWidgetState] for the lifetime of a "Werkzeuge ▸ Simulieren"
 * session: the [stateMachine] being simulated, the one-time-rendered [baseSvg] (with every
 * vertex's highlight ring present but hidden — see [DesktopRenderPipeline.prepareSimulation]),
 * and the [modelSource] script text the session was started from (used by [isStale]).
 *
 * [close] MUST be called exactly once when the session ends (Werkzeuge ▸ Simulation beenden,
 * Esc, app shutdown, or a failed [start]) — it releases [guardEvaluator]'s cached thread pool.
 * Not calling it leaks daemon threads named `kuml-sandbox-guard-*` for the JVM's lifetime.
 */
@Stable
internal class SimulationSession private constructor(
    val stateMachine: UmlStateMachine,
    val widgetState: BehaviourWidgetState,
    val baseSvg: String,
    val modelSource: String,
    val eventNames: List<String>,
    private val guardEvaluator: TimeLimitedGuardEvaluator,
) : AutoCloseable {
    var status: SimulationStatus by mutableStateOf(SimulationStatus.Idle)
        private set

    var autoAdvancing: Boolean by mutableStateOf(false)

    /** Whether the inspector/variables column is shown next to the trace. Session-only, not persisted. */
    var inspectorVisible: Boolean by mutableStateOf(true)

    /** Transition the inspector selected; its edge is highlighted in the diagram. Cleared by send, reset and scrub. */
    var selectedTransitionId: String? by mutableStateOf(null)

    /** Text of the event payload field, kept here so the inspector can evaluate guards against it. */
    var payloadDraft: String by mutableStateOf("")

    /** The session's sandboxed guard evaluator, also used (on a frozen copy) by the inspector. Never a bypass. */
    internal val inspectorEvaluator: GuardEvaluator get() = guardEvaluator

    // Variable values per trace position (see VariableTimeline). Not observable on its own: every
    // read goes through variablesAtPosition(), which depends on observable widget state.
    private val timeline = VariableTimeline(initialTraceSize = widgetState.trace.size, initial = widgetState.liveVariables)

    /** The [StepResult] of the last [send] call, if any — read by [autoAdvanceDecision]. */
    var lastResult: StepResult? by mutableStateOf(null)
        private set

    /**
     * `true` only when the LIVE end of the trace (not merely some past step) is a
     * [StepResult.Terminated] outcome.
     *
     * Review fix — deliberately also checks `!widgetState.isScrubbing`: [scrubTo] moves
     * [BehaviourWidgetState.tracePosition] back without touching [lastResult], so a session that
     * ever reached a final state would otherwise report `isTerminated == true` forever after,
     * even once the user scrubbed back to a live, non-final vertex. That locked every Step/
     * Auto-Advance/event button in `SimulationBar.kt` for the rest of the session — sending an
     * event from a scrub position is exactly the documented fork-and-continue workflow (Spez.
     * E23, see [send]'s KDoc), and [BehaviourWidgetState.forkAtScrubPosition] itself resets
     * `isTerminated = false` on the forked snapshot, so the UI must not disagree.
     */
    val isTerminated: Boolean get() = !widgetState.isScrubbing && lastResult is StepResult.Terminated

    private var closed = false

    fun activeVertexIds(): Set<String> = widgetState.currentHighlightIds()

    fun enabledEvents(): Set<String> = enabledEventNames(model = stateMachine, activeVertexIds = activeVertexIds())

    /**
     * Sends [eventName] to the running instance.
     *
     * If currently scrubbing, [BehaviourWidgetState.sendEvent] forks the trace at the scrub
     * position BEFORE dispatching — [status] is set to [SimulationStatus.DiscardedSteps] in that
     * case (Spez. E23), overriding whatever the step itself would otherwise report. No
     * confirmation dialog: the scrub UI already made "you left the live end" visible.
     */
    fun send(
        eventName: String,
        payloadJson: String = "{}",
    ): StepResult {
        // An invalid payload is rejected up front: no fork, no trace change, no lastResult.
        val parsed = EventPayloads.parse(payloadJson)
        if (parsed is PayloadParseResult.Invalid) {
            status = SimulationStatus.InvalidPayload(parsed)
            return StepResult.Error(InvalidEventPayloadException(parsed))
        }
        val discarded = widgetState.trace.size - widgetState.tracePosition
        val beforeSize = widgetState.tracePosition
        if (widgetState.isScrubbing) {
            // sendEvent would fork by itself, but without variables: fork here first so the
            // variables as of the scrub position survive, then cut the history behind it.
            widgetState.forkAtScrubPosition(variables = timeline.at(beforeSize))
            timeline.truncateAfter(beforeSize)
        }
        val result = widgetState.sendEvent(eventName = eventName, payloadJson = payloadJson)
        timeline.record(traceSize = widgetState.trace.size, variables = widgetState.liveVariables)
        selectedTransitionId = null
        lastResult = result
        val warnings = newGuardWarnings(trace = widgetState.trace, beforeSize = beforeSize)
        status =
            if (discarded > 0) {
                SimulationStatus.DiscardedSteps(discarded)
            } else {
                statusFor(result = result, warnings = warnings)
            }
        return result
    }

    /** Variables as of the current scrub position (live variables when not scrubbing). */
    fun variablesAtPosition(): Map<String, JsonElement> =
        if (widgetState.isScrubbing) timeline.at(widgetState.tracePosition) else widgetState.liveVariables

    /** Event payload parsed from [payloadDraft]; an invalid or empty draft counts as an empty payload. */
    fun draftPayload(): JsonObject = (EventPayloads.parse(payloadDraft) as? PayloadParseResult.Valid)?.payload ?: JsonObject(emptyMap())

    fun setVariable(
        name: String,
        value: JsonElement,
    ): VariableEditOutcome =
        widgetState.setVariable(name = name, value = value).also {
            if (it ==
                VariableEditOutcome.Applied
            ) {
                timeline.record(traceSize = widgetState.trace.size, variables = widgetState.liveVariables)
            }
        }

    fun removeVariable(name: String): VariableEditOutcome =
        widgetState.removeVariable(name).also {
            if (it ==
                VariableEditOutcome.Applied
            ) {
                timeline.record(traceSize = widgetState.trace.size, variables = widgetState.liveVariables)
            }
        }

    /** Temporary guard edit (never written to the script). Protected transitions need [confirmed]. */
    fun applyGuard(
        transitionId: String,
        newOcl: String,
        confirmed: Boolean,
    ): PatchOutcome = widgetState.changeGuard(transitionId = transitionId, newOcl = newOcl, confirmed = confirmed)

    /** Restores the original guard of [transitionId]. */
    fun revertGuard(transitionId: String): PatchOutcome = widgetState.revertGuard(transitionId)

    /** Number of guards that currently differ from the script's. */
    val editedGuardCount: Int get() = widgetState.editedGuardIds.size

    /** Called by the auto-advance loop in `SimulationBar.kt` when it decides to stop. */
    fun reportAutoStop(reason: AutoAdvanceStopReason) {
        autoAdvancing = false
        status = SimulationStatus.AutoStopped(reason)
    }

    /** Clears a short-lived [SimulationStatus.DiscardedSteps] notice back to [SimulationStatus.Idle] (`MainWindow`'s StatusBar auto-dismisses it after 4s). */
    fun clearDiscardedStepsNotice() {
        if (status is SimulationStatus.DiscardedSteps) status = SimulationStatus.Idle
    }

    fun reset() {
        // Full reset: guards, variables and trace go back to the script's state (payloadDraft stays).
        widgetState.resetAll()
        timeline.reset(initialTraceSize = widgetState.trace.size, initial = widgetState.liveVariables)
        selectedTransitionId = null
        lastResult = null
        status = SimulationStatus.Idle
        autoAdvancing = false
    }

    fun scrubTo(position: Int) {
        autoAdvancing = false
        selectedTransitionId = null
        widgetState.scrubTo(position)
    }

    override fun close() {
        if (closed) return
        closed = true
        autoAdvancing = false
        guardEvaluator.close()
    }

    private fun statusFor(
        result: StepResult,
        warnings: List<TraceEntry.GuardWarning>,
    ): SimulationStatus {
        // GuardWarnings win over a silent Stayed (Spez. F29) — a Stayed caused by a failed/
        // timed-out guard must be visibly distinguishable from "no transition offered at all".
        //
        // Review fix — scoped to `result is StepResult.Stayed` (it used to apply to every
        // result, [StepResult.Transitioned] included). A state can have two outgoing transitions
        // on the same event, one guarded and one not: if the guarded one's guard evaluation
        // itself fails (a `GuardResult.Failed` — a thrown/timed-out evaluation, not simply
        // resolving to `false`), the runtime logs a GuardWarning for it AND still fires the
        // unguarded transition, so the step's own result is a perfectly good Transitioned. Only a
        // *silent* Stayed needs a GuardWarning to explain why nothing happened; a Transitioned
        // must never be overridden into a red "guard not satisfied" line for a candidate
        // transition that was never actually needed.
        if (result is StepResult.Stayed) {
            val warning = warnings.lastOrNull()
            if (warning != null) {
                // Review fix — matches the shared constant instead of a bare "timed out"
                // substring, so a reword of TimeLimitedGuardEvaluator's message can't silently
                // degrade every sandbox timeout to a plain GuardFailed with no test failing.
                return if (warning.message.startsWith(TimeLimitedGuardEvaluator.TIMEOUT_MESSAGE_PREFIX)) {
                    SimulationStatus.GuardTimeout(transitionId = warning.transitionId, message = warning.message)
                } else {
                    SimulationStatus.GuardFailed(transitionId = warning.transitionId, message = warning.message)
                }
            }
        }
        return when (result) {
            is StepResult.Transitioned -> SimulationStatus.Idle
            is StepResult.Stayed -> SimulationStatus.Stayed(result.reason)
            is StepResult.GuardFailed -> SimulationStatus.GuardFailed(transitionId = result.transitionId, message = result.message)
            is StepResult.Error -> SimulationStatus.Error(result.cause.message ?: result.cause.javaClass.simpleName)
            is StepResult.Terminated -> SimulationStatus.Terminated
        }
    }

    companion object {
        /**
         * Prepares + starts a sandboxed simulation session for [script] under [themeName].
         *
         * Constructs its own [StateMachineRuntime] — sandboxed by construction (Spez. F27: "ein
         * Sicherheitsmechanismus mit Ausschalter ist keiner"), no setting or shortcut anywhere
         * bypasses [TimeLimitedGuardEvaluator] / [SandboxEffectInvoker]. If [BehaviourWidgetState]'s
         * field initializer throws (e.g. the state machine has no INITIAL pseudostate —
         * [StateMachineRuntime.start] requires exactly one), the guard evaluator's thread pool is
         * closed before returning [SimulationStartResult.Failed] — otherwise every failed start
         * would leak a `kuml-sandbox-guard-*` cached thread pool.
         */
        fun start(
            script: String,
            themeName: String,
        ): SimulationStartResult =
            when (val prepared = DesktopRenderPipeline.prepareSimulation(script = script, themeName = themeName)) {
                is SimulationPrepareResult.Unsupported -> SimulationStartResult.Unsupported(prepared.message)
                is SimulationPrepareResult.Failed -> SimulationStartResult.Failed(prepared.message)
                is SimulationPrepareResult.Ready -> {
                    val policy = SandboxPolicy(guardTimeoutMs = SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS)
                    val guards = TimeLimitedGuardEvaluator(delegate = OclGuardEvaluator(), policy = policy)
                    try {
                        val effects = SandboxEffectInvoker(EffectExecutor(policy))
                        val runtime = StateMachineRuntime(guards = guards, effects = effects)
                        val widgetState =
                            BehaviourWidgetState(
                                initialModel = prepared.stateMachine,
                                runtime = runtime,
                                editPolicy = EditPolicy.GuardsOnly,
                            )
                        SimulationStartResult.Started(
                            SimulationSession(
                                stateMachine = prepared.stateMachine,
                                widgetState = widgetState,
                                baseSvg = prepared.svg,
                                modelSource = script,
                                eventNames = allEventNames(prepared.stateMachine),
                                guardEvaluator = guards,
                            ),
                        )
                    } catch (e: Exception) {
                        guards.close()
                        SimulationStartResult.Failed(e.message ?: "Simulation konnte nicht gestartet werden")
                    }
                }
            }
    }
}
