package dev.kuml.runtime.sysml2

import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex
import java.time.Instant

/**
 * V2.0.17 — adapter that builds a [StateMachineRuntime] from a SysML 2 model
 * plus an [StmDiagram] selecting which [StateDefinition]s are part of the
 * machine.
 *
 * ## Approach: **Option A — thin wrapper** (per V2.0.17 wave plan)
 *
 * The existing [StateMachineRuntime] is tightly typed to [UmlStateMachine].
 * Rather than refactor the runtime to consume a model-agnostic
 * `RuntimeStateMachineSpec` (Option B in the wave plan — a larger,
 * cross-cutting change), V2.0.17 translates the SysML 2 elements on the fly:
 *
 *  * Each visible [StateDefinition] becomes a [UmlVertex]:
 *    * `isInitial = true`  → [UmlPseudostate] with [PseudostateKind.INITIAL]
 *    * `isFinal   = true`  → [UmlFinalState]
 *    * otherwise           → [UmlState] (with `entry` / `exit` / `doActivity`
 *      strings carried over verbatim from the SysML 2 action slots).
 *  * Each [TransitionUsage] whose *both* endpoints are in the visible state
 *    set becomes a [UmlTransition] with matching `sourceId` / `targetId` /
 *    `trigger` / `guard` / `effect` strings. Out-of-scope transitions
 *    (endpoint not selected by the diagram) are silently dropped — same
 *    "Pattern A" projection rule the V2.0.9 layout bridge uses.
 *
 * The runtime then runs unchanged: the existing [OclGuardEvaluator] handles
 * guards, action strings emit trace entries via the same `ActionInvoked` /
 * `StateEntered` / `StateExited` mechanism the UML path uses, and snapshot /
 * restore round-trip through the same `UmlStateMachine` shape.
 *
 * ### Why Option A and not Option B
 *
 * Option B (refactor [StateMachineRuntime] to a `RuntimeStateMachineSpec`
 * data class consumed by both UML and SysML 2 producers) is the long-term
 * shape, but:
 *   1. The V1.1.5 runtime is mature and well-tested; a refactor would
 *      ripple through `OclGuardEvaluator`, `StateMachineInstance`,
 *      snapshot / restore, and every existing test.
 *   2. The SysML 2 STM metamodel today is **flat** — no nested states,
 *      no orthogonal regions, no fork / join / history pseudostates.
 *      The translation surface is therefore small, and the wrapper is a
 *      faithful one-to-one mapping with no semantic gap.
 *   3. Honest scoping: the model-agnostic refactor is a separate V2.x
 *      wave (likely paired with the typed-expression-AST wave so guards
 *      and effects share the same AST shape across UML / SysML 2).
 *
 * ## V2.0.17 MVP scope
 *
 *  * Flat SysML 2 state machines (the metamodel doesn't carry nested states
 *    yet — separate metamodel wave).
 *  * Guards evaluated by the existing [OclGuardEvaluator] (raw-string OCL).
 *  * Effects + entry / exit / do actions emitted as trace entries; no
 *    side-effecting execution (sandbox is a V2.x wave).
 *
 * ## Out of V2.0.17 scope (// V2.x:)
 *
 *  * Hierarchical SysML 2 state machines.
 *  * Typed expression AST for guards / effects.
 *  * Side-effecting action execution (sandbox).
 *  * Model-agnostic runtime refactor (Option B).
 *  * Live-Mirror integration (ADR-0007 Stufe B).
 *  * Chain-Backed-Models hookup.
 *  * Behaviour Widget integration (Compose).
 */
public object Sysml2StateMachineAdapter {
    /**
     * Build a [UmlStateMachine] from the visible slice of [model] selected
     * by [diagram]. Useful for tests that want to inspect the translation
     * shape, or for callers that want to keep the runtime construction
     * separate (e.g. to pass a custom guard evaluator or clock).
     *
     * @param model SysML 2 model holding the state definitions + transition usages.
     * @param diagram STM diagram whose [StmDiagram.elementIds] select which
     *   states participate in the machine.
     * @return the translated [UmlStateMachine] — id and name are derived from
     *   the diagram name to keep `instance.model.id` meaningful in traces.
     * @throws IllegalStateException if no visible state is marked `isInitial`.
     */
    public fun toUmlStateMachine(
        model: Sysml2Model,
        diagram: StmDiagram,
    ): UmlStateMachine {
        val visibleIds = diagram.elementIds.toSet()
        val visibleStates: List<StateDefinition> =
            model.definitions
                .filterIsInstance<StateDefinition>()
                .filter { it.id in visibleIds }

        if (visibleStates.none { it.isInitial }) {
            error(
                "SysML 2 STM '${diagram.name}' has no visible initial state. " +
                    "Mark exactly one StateDefinition with isInitial = true and include " +
                    "it in the diagram's elementIds. The Behaviour-Runtime requires " +
                    "a single top-level INITIAL pseudostate (see V1.1.5 operational " +
                    "semantics).",
            )
        }

        val vertices: List<UmlVertex> = visibleStates.map { it.toVertex() }
        val transitions: List<UmlTransition> =
            model.usages
                .filterIsInstance<TransitionUsage>()
                .filter { it.sourceStateId in visibleIds && it.targetStateId in visibleIds }
                .map { it.toUmlTransition() }

        // Derive a stable id from the diagram name (lower-cased, spaces to
        // dashes). The id surfaces in trace `modelId` fields, so a
        // human-readable form is friendlier than a synthetic UUID.
        val machineId = diagram.name.lowercase().replace(Regex("\\s+"), "-")
        return UmlStateMachine(
            id = machineId,
            name = diagram.name,
            vertices = vertices,
            transitions = transitions,
        )
    }

    /**
     * Build a runnable [StateMachineRuntime] from a SysML 2 model + STM
     * diagram and immediately call [StateMachineRuntime.start] so the
     * returned pair is ready to consume events via
     * [StateMachineRuntime.step].
     *
     * Equivalent to:
     * ```kotlin
     * val sm = Sysml2StateMachineAdapter.toUmlStateMachine(model, diagram)
     * val runtime = StateMachineRuntime(guards, clock)
     * val instance = runtime.start(sm)
     * ```
     *
     * Callers that need to inject a different [dev.kuml.runtime.GuardEvaluator]
     * or clock should use [toUmlStateMachine] directly and construct the
     * runtime themselves.
     *
     * @param model SysML 2 model holding the state definitions + transition usages.
     * @param diagram STM diagram selecting the participating states.
     * @param clock clock source for trace timestamps; defaults to
     *   [Instant.now] (the same default the UML path uses).
     * @return a [Sysml2RuntimeHandle] bundling the runtime, its started
     *   instance, and the translated [UmlStateMachine] for downstream
     *   inspection (e.g. trace assertions).
     */
    public fun runtimeFor(
        model: Sysml2Model,
        diagram: StmDiagram,
        clock: () -> Instant = Instant::now,
    ): Sysml2RuntimeHandle {
        val sm = toUmlStateMachine(model, diagram)
        val runtime = StateMachineRuntime(guards = OclGuardEvaluator(), clock = clock)
        val instance = runtime.start(sm)
        return Sysml2RuntimeHandle(runtime = runtime, instance = instance, stateMachine = sm)
    }

    /** Translate a single [StateDefinition] to the right [UmlVertex] subtype. */
    private fun StateDefinition.toVertex(): UmlVertex =
        when {
            isInitial ->
                UmlPseudostate(
                    id = id,
                    name = name,
                    kind = PseudostateKind.INITIAL,
                )
            isFinal ->
                UmlFinalState(
                    id = id,
                    name = name,
                )
            else ->
                UmlState(
                    id = id,
                    name = name,
                    entry = entryAction,
                    exit = exitAction,
                    doActivity = doAction,
                )
        }

    /** Translate a [TransitionUsage] to a [UmlTransition] one-to-one. */
    private fun TransitionUsage.toUmlTransition(): UmlTransition =
        UmlTransition(
            id = id,
            sourceId = sourceStateId,
            targetId = targetStateId,
            trigger = trigger,
            guard = guard,
            effect = effect,
        )
}

/**
 * Result of [Sysml2StateMachineAdapter.runtimeFor] — bundles the runtime,
 * its started [dev.kuml.runtime.StateMachineInstance] and the translated
 * [UmlStateMachine] so callers can introspect the translation without
 * re-running the adapter.
 *
 * @property runtime the [StateMachineRuntime] driving the instance.
 * @property instance the started [dev.kuml.runtime.StateMachineInstance].
 * @property stateMachine the translated [UmlStateMachine] (the same instance
 *   the runtime holds in [dev.kuml.runtime.StateMachineInstance.model]).
 */
public data class Sysml2RuntimeHandle(
    val runtime: StateMachineRuntime,
    val instance: dev.kuml.runtime.StateMachineInstance,
    val stateMachine: UmlStateMachine,
)
