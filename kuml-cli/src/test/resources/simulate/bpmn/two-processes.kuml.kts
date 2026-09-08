@file:Suppress("unused")

import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

/**
 * Two independent processes; the script's own `diagram(...)` declaration
 * points at "procA" by default — exercises `--process procB` overriding it
 * (ADR-0015).
 */
bpmnModel(name = "TwoProcesses") {
    process(id = "procA", name = "Process A") {
        val start = startEvent()
        val t = task(name = "onlyA")
        val end = endEvent()
        sequenceFlow(from = start, to = t)
        sequenceFlow(from = t, to = end)
    }
    process(id = "procB", name = "Process B") {
        val start = startEvent()
        val t = task(name = "onlyB")
        val end = endEvent()
        sequenceFlow(from = start, to = t)
        sequenceFlow(from = t, to = end)
    }
    diagram(name = "Process A View", processId = "procA")
}
