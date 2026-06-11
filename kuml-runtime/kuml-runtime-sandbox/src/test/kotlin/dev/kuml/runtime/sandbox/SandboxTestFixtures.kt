package dev.kuml.runtime.sandbox

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex

/** Build a minimal state machine and return a started instance with blank variables. */
internal fun emptyInstance(): StateMachineInstance {
    val sm =
        UmlStateMachine(
            id = "test",
            name = "test",
            vertices =
                listOf(
                    UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL),
                    UmlState(id = "A", name = "A"),
                ),
            transitions = listOf(UmlTransition(id = "t0", sourceId = "init", targetId = "A")),
        )
    return StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue).start(sm)
}

/** Convenience: creates a simple state machine with a single state that has entry/exit/doActivity/effect. */
internal fun smWith(
    entry: String? = null,
    exit: String? = null,
    doActivity: String? = null,
    transitionEffect: String? = null,
    transitionGuard: String? = null,
): UmlStateMachine {
    val vertices: List<UmlVertex> =
        listOf(
            UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL),
            UmlState(id = "A", name = "A", exit = exit),
            UmlState(id = "B", name = "B", entry = entry, doActivity = doActivity),
            UmlFinalState(id = "end", name = "end"),
        )
    val transitions =
        listOf(
            UmlTransition(id = "t0", sourceId = "init", targetId = "A"),
            UmlTransition(
                id = "t1",
                sourceId = "A",
                targetId = "B",
                trigger = "go",
                guard = transitionGuard,
                effect = transitionEffect,
            ),
        )
    return UmlStateMachine(id = "sm", name = "sm", vertices = vertices, transitions = transitions)
}

internal val noEvent: Event = Event(name = "")
internal val goEvent: Event = Event(name = "go")
