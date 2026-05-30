package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex
import dev.kuml.uml.ids.UmlIds

// ── state() ──────────────────────────────────────────────────────────────────

/**
 * Adds a simple [UmlState] to the enclosing state machine.
 *
 * ```kotlin
 * stateDiagram("Order Lifecycle") {
 *     val draft = state("Draft") {
 *         entry = "validate()"
 *         doActivity = "notifyCustomer()"
 *     }
 * }
 * ```
 */
fun UmlStateMachineScope.state(
    name: String,
    id: String? = null,
    block: StateBodyBuilder.() -> Unit = {},
): UmlState {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.vertex(stateMachineId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val body = StateBodyBuilder().apply(block)
    val s =
        UmlState(
            id = resolvedId,
            name = name,
            entry = body.entry,
            exit = body.exit,
            doActivity = body.doActivity,
            stereotypes = body.stereotypes.toList(),
        )
    addVertex(s)
    return s
}

fun UmlCompositeStateScope.state(
    name: String,
    id: String? = null,
    block: StateBodyBuilder.() -> Unit = {},
): UmlState {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(parentStateId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val body = StateBodyBuilder().apply(block)
    val s =
        UmlState(
            id = resolvedId,
            name = name,
            entry = body.entry,
            exit = body.exit,
            doActivity = body.doActivity,
            stereotypes = body.stereotypes.toList(),
        )
    addSubstate(s)
    return s
}

@KumlDsl
class StateBodyBuilder internal constructor() {
    var entry: String? = null
    var exit: String? = null
    var doActivity: String? = null
    val stereotypes: MutableList<String> = mutableListOf()
}

// ── Pseudostate helper (Variante B) ──────────────────────────────────────────

/**
 * Shared logic for building a [UmlPseudostate] — Variante B with explicit [takenIds] parameter.
 *
 * Callers pass [takenIds] from their scope, making this a pure helper with no magic.
 */
private fun pseudoOn(
    parentId: String,
    takenIds: MutableSet<String>,
    name: String,
    kind: PseudostateKind,
    explicitId: String?,
): UmlPseudostate {
    val resolvedId =
        explicitId ?: UmlIds.disambiguate(
            candidate = UmlIds.vertex(parentId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    return UmlPseudostate(id = resolvedId, name = name, kind = kind)
}

/** Same as [pseudoOn] but derives the child ID from [UmlIds.child] for composite state sub-scopes. */
private fun pseudoOnChild(
    parentId: String,
    takenIds: MutableSet<String>,
    name: String,
    kind: PseudostateKind,
    explicitId: String?,
): UmlPseudostate {
    val resolvedId =
        explicitId ?: UmlIds.disambiguate(
            candidate = UmlIds.child(parentId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    return UmlPseudostate(id = resolvedId, name = name, kind = kind)
}

// ── initialState() ───────────────────────────────────────────────────────────

/**
 * Adds an initial pseudostate to the enclosing state machine.
 *
 * Every state machine should have exactly one initial pseudostate.
 * V1 does not enforce this — it's a renderer-level concern.
 */
fun UmlStateMachineScope.initialState(
    name: String = "initial",
    id: String? = null,
): UmlPseudostate =
    pseudoOn(stateMachineId, takenIds, name, PseudostateKind.INITIAL, id)
        .also { addVertex(it) }

fun UmlCompositeStateScope.initialState(
    name: String = "initial",
    id: String? = null,
): UmlPseudostate =
    pseudoOnChild(parentStateId, takenIds, name, PseudostateKind.INITIAL, id)
        .also { addSubstate(it) }

// ── finalState() ─────────────────────────────────────────────────────────────

/**
 * Adds a final state to the enclosing state machine.
 */
fun UmlStateMachineScope.finalState(
    name: String,
    id: String? = null,
): UmlFinalState {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.vertex(stateMachineId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val fs = UmlFinalState(id = resolvedId, name = name)
    addVertex(fs)
    return fs
}

fun UmlCompositeStateScope.finalState(
    name: String,
    id: String? = null,
): UmlFinalState {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(parentStateId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val fs = UmlFinalState(id = resolvedId, name = name)
    addSubstate(fs)
    return fs
}

// ── choice, fork, join, junction (Convenience-Wrapper) ───────────────────────

fun UmlStateMachineScope.choice(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOn(stateMachineId, takenIds, name, PseudostateKind.CHOICE, id).also { addVertex(it) }

fun UmlCompositeStateScope.choice(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOnChild(parentStateId, takenIds, name, PseudostateKind.CHOICE, id).also { addSubstate(it) }

fun UmlStateMachineScope.fork(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOn(stateMachineId, takenIds, name, PseudostateKind.FORK, id).also { addVertex(it) }

fun UmlCompositeStateScope.fork(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOnChild(parentStateId, takenIds, name, PseudostateKind.FORK, id).also { addSubstate(it) }

fun UmlStateMachineScope.join(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOn(stateMachineId, takenIds, name, PseudostateKind.JOIN, id).also { addVertex(it) }

fun UmlCompositeStateScope.join(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOnChild(parentStateId, takenIds, name, PseudostateKind.JOIN, id).also { addSubstate(it) }

fun UmlStateMachineScope.junction(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOn(stateMachineId, takenIds, name, PseudostateKind.JUNCTION, id).also { addVertex(it) }

fun UmlCompositeStateScope.junction(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOnChild(parentStateId, takenIds, name, PseudostateKind.JUNCTION, id).also { addSubstate(it) }

fun UmlStateMachineScope.shallowHistory(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOn(stateMachineId, takenIds, name, PseudostateKind.SHALLOW_HISTORY, id).also { addVertex(it) }

fun UmlCompositeStateScope.shallowHistory(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOnChild(parentStateId, takenIds, name, PseudostateKind.SHALLOW_HISTORY, id).also { addSubstate(it) }

fun UmlStateMachineScope.deepHistory(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOn(stateMachineId, takenIds, name, PseudostateKind.DEEP_HISTORY, id).also { addVertex(it) }

fun UmlCompositeStateScope.deepHistory(
    name: String,
    id: String? = null,
): UmlPseudostate = pseudoOnChild(parentStateId, takenIds, name, PseudostateKind.DEEP_HISTORY, id).also { addSubstate(it) }

// ── transition() ─────────────────────────────────────────────────────────────

/**
 * Creates a [UmlTransition] between two vertices at the state-machine level.
 *
 * ```kotlin
 * transition(draft, confirmed) {
 *     trigger = "confirm()"
 *     guard = "[isValid]"
 *     effect = "logConfirmation()"
 * }
 * ```
 *
 * Even when [source] and [target] are substates of composite states,
 * the transition is registered on the enclosing state machine, not on
 * the composite — matching UML semantics.
 */
fun UmlStateMachineScope.transition(
    source: UmlVertex,
    target: UmlVertex,
    id: String? = null,
    block: TransitionBuilder.() -> Unit = {},
): UmlTransition =
    transitionByIds(
        sourceId = source.id,
        targetId = target.id,
        sourceName = source.name,
        targetName = target.name,
        explicitId = id,
        block = block,
    )

fun UmlStateMachineScope.transitionByIds(
    sourceId: String,
    targetId: String,
    sourceName: String = sourceId.substringAfterLast(UmlIds.SEP),
    targetName: String = targetId.substringAfterLast(UmlIds.SEP),
    explicitId: String? = null,
    block: TransitionBuilder.() -> Unit = {},
): UmlTransition {
    val baseId = UmlIds.transition(stateMachineId, sourceName, targetName)
    val resolvedId = explicitId ?: UmlIds.disambiguate(baseId, takenIds)
    takenIds += resolvedId
    val body = TransitionBuilder().apply(block)
    val t =
        UmlTransition(
            id = resolvedId,
            sourceId = sourceId,
            targetId = targetId,
            trigger = body.trigger,
            guard = body.guard,
            effect = body.effect,
        )
    addTransition(t)
    return t
}

@KumlDsl
class TransitionBuilder internal constructor() {
    var trigger: String? = null
    var guard: String? = null
    var effect: String? = null
}

// ── compositeState() ─────────────────────────────────────────────────────────

/**
 * Adds a composite [UmlState] — a state that contains [substates].
 *
 * Substates declared inside the [block] are stored in [UmlState.substates]
 * and get IDs of the form `<parentStateId>::<substateName>`. Transitions
 * between substates are declared at the enclosing state-machine scope,
 * not inside the composite.
 *
 * ```kotlin
 * val processing = compositeState("Processing") {
 *     val picking = state("Picking")
 *     val packing = state("Packing")
 * }
 * transition(processing, draft)              // outer
 * transition(picking, packing)               // inner (declared at SM scope)
 * ```
 */
fun UmlStateMachineScope.compositeState(
    name: String,
    id: String? = null,
    block: CompositeStateBuilder.() -> Unit,
): UmlState {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.vertex(stateMachineId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val builder = CompositeStateBuilder(parentStateId = resolvedId, takenIds = takenIds).apply(block)
    val composite =
        UmlState(
            id = resolvedId,
            name = name,
            entry = builder.entry,
            exit = builder.exit,
            doActivity = builder.doActivity,
            substates = builder.substates.toList(),
            stereotypes = builder.stereotypes.toList(),
        )
    addVertex(composite)
    return composite
}

fun UmlCompositeStateScope.compositeState(
    name: String,
    id: String? = null,
    block: CompositeStateBuilder.() -> Unit,
): UmlState {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(parentStateId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val builder = CompositeStateBuilder(parentStateId = resolvedId, takenIds = takenIds).apply(block)
    val composite =
        UmlState(
            id = resolvedId,
            name = name,
            entry = builder.entry,
            exit = builder.exit,
            doActivity = builder.doActivity,
            substates = builder.substates.toList(),
            stereotypes = builder.stereotypes.toList(),
        )
    addSubstate(composite)
    return composite
}

@KumlDsl
class CompositeStateBuilder internal constructor(
    override val parentStateId: String,
    override val takenIds: MutableSet<String>,
) : UmlCompositeStateScope {
    var entry: String? = null
    var exit: String? = null
    var doActivity: String? = null
    val stereotypes: MutableList<String> = mutableListOf()

    internal val substates = mutableListOf<UmlVertex>()

    override fun addSubstate(vertex: UmlVertex) {
        substates += vertex
    }
}
