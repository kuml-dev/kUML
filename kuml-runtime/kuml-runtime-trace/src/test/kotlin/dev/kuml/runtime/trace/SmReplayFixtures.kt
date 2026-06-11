package dev.kuml.runtime.trace

import dev.kuml.runtime.Event
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Build a minimal state machine: INIT → A --"go"→ B → FINAL */
internal fun twoStateSmWithFinal(): UmlStateMachine {
    val init = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
    val stateA = UmlState(id = "A", name = "A")
    val stateB = UmlState(id = "B", name = "B")
    val fin = UmlFinalState(id = "final", name = "final")
    return UmlStateMachine(
        id = "TestSm",
        name = "TestSm",
        vertices = listOf(init, stateA, stateB, fin),
        transitions =
            listOf(
                UmlTransition(id = "t0", sourceId = "init", targetId = "A"),
                UmlTransition(id = "t1", sourceId = "A", targetId = "B", trigger = "go"),
                UmlTransition(id = "t2", sourceId = "B", targetId = "final", trigger = "done"),
            ),
    )
}

/** Build a minimal state machine: INIT → A --"go"→ FINAL */
internal fun oneEventSm(): UmlStateMachine {
    val init = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
    val stateA = UmlState(id = "A", name = "A")
    val fin = UmlFinalState(id = "final", name = "final")
    return UmlStateMachine(
        id = "OneEventSm",
        name = "OneEventSm",
        vertices = listOf(init, stateA, fin),
        transitions =
            listOf(
                UmlTransition(id = "t0", sourceId = "init", targetId = "A"),
                UmlTransition(id = "t1", sourceId = "A", targetId = "final", trigger = "go"),
            ),
    )
}

/** Run [model] with [events] using a deterministic clock and return the resulting TraceFile. */
internal fun simulateToTraceFile(
    model: UmlStateMachine,
    events: List<Event>,
): TraceFile {
    val clock = AtomicLong(0L)
    val clockFn: () -> Instant = { Instant.ofEpochMilli(clock.getAndIncrement()) }
    val runtime = StateMachineRuntime(guards = OclGuardEvaluator(), clock = clockFn)
    val instance = runtime.start(model)
    for (ev in events) {
        if (instance.isTerminated) break
        runtime.step(instance, ev)
    }
    return TraceFile(modelId = model.id, entries = instance.trace)
}

/** Build a fake Activity-flavoured trace (contains TokenPlaced). */
internal fun activityTraceFile(): TraceFile =
    TraceFile(
        modelId = "ActivityModel",
        entries =
            listOf(
                TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "n1", clock = 0L),
                TraceEntry.TokenConsumed(seqNo = 1L, timestamp = "", nodeId = "n1", clock = 1L),
                TraceEntry.ActivityTerminated(seqNo = 2L, timestamp = "", clock = 2L),
            ),
    )

internal fun smOf(
    name: String,
    vertices: List<UmlVertex>,
    transitions: List<UmlTransition>,
): UmlStateMachine = UmlStateMachine(id = name, name = name, vertices = vertices, transitions = transitions)
