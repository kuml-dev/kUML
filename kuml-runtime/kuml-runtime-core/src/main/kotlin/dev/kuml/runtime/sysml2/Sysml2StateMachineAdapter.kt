package dev.kuml.runtime.sysml2

import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.ProseGuardPolicy
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StmRuntimeLimits
import dev.kuml.sysml2.GuardKind
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionTimer
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.constraint.StmSeverity
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import dev.kuml.sysml2.visibleStateIds
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlRegion
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTimer
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
 *  * Wave A2 lifted the flat-only restriction: composite states, orthogonal regions,
 *    choice / junction / fork / join / history pseudo-states, `after` / `at` timers and
 *    prose guards are simulated (see [dev.kuml.runtime.StateMachineRuntime]).
 *  * Guards evaluated by the existing [OclGuardEvaluator] (raw-string OCL).
 *  * Effects + entry / exit / do actions emitted as trace entries; no
 *    side-effecting execution (sandbox is a V2.x wave).
 *
 * ## Out of V2.0.17 scope (// V2.x:)
 *
 *  * Typed expression AST for guards / effects.
 *  * Side-effecting action execution (sandbox).
 *  * Model-agnostic runtime refactor (Option B).
 *  * Live-Mirror integration (ADR-0007 Stufe B).
 *  * Chain-Backed-Models hookup.
 *  * Behaviour Widget integration (Compose).
 */
public object Sysml2StateMachineAdapter {
    /** Start of the deterministic virtual clock when a machine has timers and no explicit start time. */
    public val DEFAULT_VIRTUAL_START: Instant = Instant.parse("2000-01-01T00:00:00Z")

    /**
     * Human-readable reason why [diagram] cannot be simulated, or `null` when it can: the first
     * checker error (see [Sysml2StmChecker]) naming its code, or a missing initial state.
     */
    public fun simulationBlockReason(
        model: Sysml2Model,
        diagram: StmDiagram,
    ): String? {
        val error = Sysml2StmChecker.check(model = model, diagram = diagram).firstOrNull { it.severity == StmSeverity.Error }
        return error?.let { "state machine error ${it.code} at '${it.elementId.take(80)}': ${it.message.take(200)}" }
    }

    /**
     * The virtual-clock start for [machine]: [explicit] when given, [DEFAULT_VIRTUAL_START] when the
     * machine has timers, `null` (wall clock, unchanged trace timestamps) otherwise.
     */
    public fun virtualStartFor(
        machine: UmlStateMachine,
        explicit: Instant?,
    ): Instant? = explicit ?: DEFAULT_VIRTUAL_START.takeIf { machine.transitions.any { it.timer != null } }

    /**
     * Build a [UmlStateMachine] from the visible slice of [model] selected
     * by [diagram]. Useful for tests that want to inspect the translation
     * shape, or for callers that want to keep the runtime construction
     * separate (e.g. to pass a custom guard evaluator or clock).
     *
     * Composite states become [UmlState]s with `substates` (implicit region) and `regions`
     * (declared orthogonal regions); pseudo-states map onto [PseudostateKind]; timers and prose
     * guards are carried over.
     *
     * @param model SysML 2 model holding the state definitions + transition usages.
     * @param diagram STM diagram whose visible states (see [visibleStateIds]) select which
     *   states participate in the machine.
     * @return the translated [UmlStateMachine] — id and name are derived from
     *   the diagram name to keep `instance.model.id` meaningful in traces.
     * @throws IllegalStateException if the machine has structural errors or no initial state.
     */
    public fun toUmlStateMachine(
        model: Sysml2Model,
        diagram: StmDiagram,
    ): UmlStateMachine {
        simulationBlockReason(model = model, diagram = diagram)?.let { reason ->
            error("State machine '${diagram.name}' cannot be simulated: $reason")
        }

        val visibleIds = diagram.visibleStateIds(model)
        val visibleStates: List<StateDefinition> =
            model.definitions
                .filterIsInstance<StateDefinition>()
                .filter { it.id in visibleIds }

        if (visibleStates.none { it.effectiveKind == StateKind.Initial }) {
            error(
                "SysML 2 STM '${diagram.name}' has no visible initial state. " +
                    "Mark exactly one StateDefinition with isInitial = true and include " +
                    "it in the diagram's elementIds. The Behaviour-Runtime requires " +
                    "a single top-level INITIAL pseudostate (see V1.1.5 operational " +
                    "semantics).",
            )
        }

        val childrenOf: Map<String?, List<StateDefinition>> = visibleStates.groupBy { it.parentStateId }

        fun build(state: StateDefinition): UmlVertex = state.toVertex(children = childrenOf[state.id].orEmpty(), build = ::build)

        val vertices: List<UmlVertex> = childrenOf[null].orEmpty().map { build(it) }
        val transitions: List<UmlTransition> =
            model.usages
                .filterIsInstance<TransitionUsage>()
                .filter { it.sourceStateId in visibleIds && it.targetStateId in visibleIds }
                .map { it.toUmlTransition() }

        // V2.0.20a — pre-parse STM guards into the typed AST (best-effort).
        // Parse failures are silently ignored here; the legacy evaluator will
        // handle them at runtime.  Pre-parsing seeds the OclGuardEvaluator
        // cache so the first event dispatch doesn't pay the parse cost.
        transitions.forEach { tr ->
            if (!tr.guard.isNullOrBlank() && !tr.guardIsProse) {
                val errors = mutableListOf<dev.kuml.expr.ParseError>()
                OclLikeExpressionParser.tryParse(input = tr.guard!!, errors = errors)
                // Parse failures are intentionally not logged to avoid noise;
                // the legacy evaluator will handle them at runtime.
            }
        }

        // V2.0.20b — pre-parse STM effect/action strings using the effect sub-grammar.
        // Covers transition effects and state entry/exit/do action bodies.
        // Parse failures are silently ignored — action bodies are informational
        // in the MVP (side-effecting execution is V2.x) and must not disrupt runtime.
        visibleStates.forEach { state ->
            listOfNotNull(state.entryAction, state.exitAction, state.doAction).forEach { action ->
                if (action.isNotBlank()) {
                    val errors = mutableListOf<dev.kuml.expr.ParseError>()
                    OclLikeExpressionParser.tryParseEffects(input = action, errors = errors)
                }
            }
        }

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
        startTime: Instant? = null,
        proseGuards: ProseGuardPolicy = ProseGuardPolicy.Block,
        limits: StmRuntimeLimits = StmRuntimeLimits.DEFAULT,
    ): Sysml2RuntimeHandle {
        val sm = toUmlStateMachine(model = model, diagram = diagram)
        val runtime =
            StateMachineRuntime(
                guards = OclGuardEvaluator(),
                clock = clock,
                limits = limits,
                proseGuards = proseGuards,
                virtualStart = virtualStartFor(machine = sm, explicit = startTime),
            )
        val instance = runtime.start(sm)
        return Sysml2RuntimeHandle(runtime = runtime, instance = instance, stateMachine = sm)
    }

    /** Translate a single [StateDefinition] (with its already translated [children]) to the right [UmlVertex] subtype. */
    private fun StateDefinition.toVertex(
        children: List<StateDefinition>,
        build: (StateDefinition) -> UmlVertex,
    ): UmlVertex =
        when (effectiveKind) {
            StateKind.Initial -> UmlPseudostate(id = id, name = name, kind = PseudostateKind.INITIAL)
            StateKind.Final -> UmlFinalState(id = id, name = name)
            StateKind.Choice -> UmlPseudostate(id = id, name = name, kind = PseudostateKind.CHOICE)
            StateKind.Junction -> UmlPseudostate(id = id, name = name, kind = PseudostateKind.JUNCTION)
            StateKind.Fork -> UmlPseudostate(id = id, name = name, kind = PseudostateKind.FORK)
            StateKind.Join -> UmlPseudostate(id = id, name = name, kind = PseudostateKind.JOIN)
            StateKind.ShallowHistory -> UmlPseudostate(id = id, name = name, kind = PseudostateKind.SHALLOW_HISTORY)
            StateKind.DeepHistory -> UmlPseudostate(id = id, name = name, kind = PseudostateKind.DEEP_HISTORY)
            StateKind.State -> {
                val declared = regions.map { it.id }.toSet()
                // Children without a (declared) region live in the implicit region; the others in their explicit one.
                val implicit = children.filter { it.regionId == null || it.regionId !in declared }
                UmlState(
                    id = id,
                    name = name,
                    entry = entryAction,
                    exit = exitAction,
                    doActivity = doAction,
                    substates = implicit.map(build),
                    regions =
                        regions.map { r ->
                            UmlRegion(id = r.id, name = r.name, vertices = children.filter { it.regionId == r.id }.map(build))
                        },
                )
            }
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
            timer =
                when (val t = timer) {
                    is TransitionTimer.After -> UmlTimer.After(durationMs = t.duration.inWholeMilliseconds)
                    is TransitionTimer.At -> UmlTimer.At(epochMs = t.instant.toEpochMilliseconds())
                    null -> null
                },
            guardIsProse = guardKind == GuardKind.Prose,
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
