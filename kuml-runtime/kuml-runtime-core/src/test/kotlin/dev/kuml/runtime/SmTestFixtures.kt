package dev.kuml.runtime

import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex

/**
 * Build a state machine from a fluent vertex/transition list — keeps the tests readable.
 */
internal fun smOf(
    name: String,
    vertices: List<UmlVertex>,
    transitions: List<UmlTransition>,
): UmlStateMachine = UmlStateMachine(id = name, name = name, vertices = vertices, transitions = transitions)

internal fun initial(id: String = "init"): UmlPseudostate = UmlPseudostate(id = id, name = id, kind = PseudostateKind.INITIAL)

internal fun choice(id: String): UmlPseudostate = UmlPseudostate(id = id, name = id, kind = PseudostateKind.CHOICE)

internal fun history(id: String): UmlPseudostate = UmlPseudostate(id = id, name = id, kind = PseudostateKind.SHALLOW_HISTORY)

internal fun state(
    id: String,
    entry: String? = null,
    exit: String? = null,
    doActivity: String? = null,
    substates: List<UmlVertex> = emptyList(),
): UmlState =
    UmlState(
        id = id,
        name = id,
        entry = entry,
        exit = exit,
        doActivity = doActivity,
        substates = substates,
    )

internal fun finalState(id: String = "final"): UmlFinalState = UmlFinalState(id = id, name = id)

internal fun trans(
    id: String,
    from: String,
    to: String,
    trigger: String? = null,
    guard: String? = null,
    effect: String? = null,
): UmlTransition =
    UmlTransition(
        id = id,
        sourceId = from,
        targetId = to,
        trigger = trigger,
        guard = guard,
        effect = effect,
    )
