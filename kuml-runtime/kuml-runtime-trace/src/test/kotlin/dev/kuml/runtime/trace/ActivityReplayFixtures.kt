package dev.kuml.runtime.trace

import dev.kuml.runtime.TraceFile
import dev.kuml.runtime.activity.ActivityEdgeSpec
import dev.kuml.runtime.activity.ActivityNodeSpec
import dev.kuml.runtime.activity.ActivityRuntime
import dev.kuml.runtime.activity.ActivityRuntimeSpec
import dev.kuml.sysml2.ActivityNodeKind

/**
 * simpleLinearActivity — Initial → DoWork (Action) → Final
 */
internal fun simpleLinearActivity(): ActivityRuntime {
    val nodes =
        mapOf(
            "init" to ActivityNodeSpec(id = "init", kind = ActivityNodeKind.Initial),
            "work" to ActivityNodeSpec(id = "work", kind = ActivityNodeKind.Action, actionBody = "execute()"),
            "fin" to ActivityNodeSpec(id = "fin", kind = ActivityNodeKind.Final),
        )
    val edges =
        listOf(
            ActivityEdgeSpec(id = "e1", sourceNodeId = "init", targetNodeId = "work"),
            ActivityEdgeSpec(id = "e2", sourceNodeId = "work", targetNodeId = "fin"),
        )
    return ActivityRuntime(spec = ActivityRuntimeSpec(nodes = nodes, edges = edges))
}

/**
 * decisionActivity — Initial → Decision → A (if valid=true) or B (if valid=false) → Final
 *
 * Edges:
 *   e1: init → decide
 *   e2: decide → actionA   guard="valid"
 *   e3: decide → actionB   guard="not valid"
 *   e4: actionA → fin
 *   e5: actionB → fin
 */
internal fun decisionActivity(): ActivityRuntime {
    val nodes =
        mapOf(
            "init" to ActivityNodeSpec(id = "init", kind = ActivityNodeKind.Initial),
            "decide" to ActivityNodeSpec(id = "decide", kind = ActivityNodeKind.Decision),
            "actionA" to ActivityNodeSpec(id = "actionA", kind = ActivityNodeKind.Action, actionBody = "pathA()"),
            "actionB" to ActivityNodeSpec(id = "actionB", kind = ActivityNodeKind.Action, actionBody = "pathB()"),
            "fin" to ActivityNodeSpec(id = "fin", kind = ActivityNodeKind.Final),
        )
    val edges =
        listOf(
            ActivityEdgeSpec(id = "e1", sourceNodeId = "init", targetNodeId = "decide"),
            ActivityEdgeSpec(id = "e2", sourceNodeId = "decide", targetNodeId = "actionA", guard = "valid"),
            ActivityEdgeSpec(id = "e3", sourceNodeId = "decide", targetNodeId = "actionB", guard = "not valid"),
            ActivityEdgeSpec(id = "e4", sourceNodeId = "actionA", targetNodeId = "fin"),
            ActivityEdgeSpec(id = "e5", sourceNodeId = "actionB", targetNodeId = "fin"),
        )
    return ActivityRuntime(spec = ActivityRuntimeSpec(nodes = nodes, edges = edges))
}

/**
 * forkJoinActivity — Initial → Fork → (actionA, actionB) → Join → Final
 */
internal fun forkJoinActivity(): ActivityRuntime {
    val nodes =
        mapOf(
            "init" to ActivityNodeSpec(id = "init", kind = ActivityNodeKind.Initial),
            "fork" to ActivityNodeSpec(id = "fork", kind = ActivityNodeKind.Fork),
            "actionA" to ActivityNodeSpec(id = "actionA", kind = ActivityNodeKind.Action, actionBody = "taskA()"),
            "actionB" to ActivityNodeSpec(id = "actionB", kind = ActivityNodeKind.Action, actionBody = "taskB()"),
            "join" to ActivityNodeSpec(id = "join", kind = ActivityNodeKind.Join),
            "fin" to ActivityNodeSpec(id = "fin", kind = ActivityNodeKind.Final),
        )
    val edges =
        listOf(
            ActivityEdgeSpec(id = "e1", sourceNodeId = "init", targetNodeId = "fork"),
            ActivityEdgeSpec(id = "e2", sourceNodeId = "fork", targetNodeId = "actionA"),
            ActivityEdgeSpec(id = "e3", sourceNodeId = "fork", targetNodeId = "actionB"),
            ActivityEdgeSpec(id = "e4", sourceNodeId = "actionA", targetNodeId = "join"),
            ActivityEdgeSpec(id = "e5", sourceNodeId = "actionB", targetNodeId = "join"),
            ActivityEdgeSpec(id = "e6", sourceNodeId = "join", targetNodeId = "fin"),
        )
    return ActivityRuntime(spec = ActivityRuntimeSpec(nodes = nodes, edges = edges))
}

/**
 * Simulate [runtime] with [eventContext] and return the resulting TraceFile.
 * This calls start() + run() and combines startTrace + runTrace, mirroring ActivityTraceReplayer.
 */
internal fun simulateActToTraceFile(
    runtime: ActivityRuntime,
    eventContext: Map<String, Any> = emptyMap(),
    modelId: String? = null,
): TraceFile {
    val (initInstance, startTrace) = runtime.start(eventContext)
    val (_, runTrace) = runtime.run(initial = initInstance, eventContext = eventContext)
    return TraceFile(modelId = modelId, entries = startTrace + runTrace)
}
