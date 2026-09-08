@file:Suppress("unused")

import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

/**
 * Same two independent processes as `two-processes.kuml.kts`, but this script
 * declares a [dev.kuml.bpmn.model.ProcessDiagram] for BOTH processes — exercises
 * `SimulateCommand.resolveProcessDiagram`'s "more than one declared process
 * diagram, no --process given" warning path (ADR-0015).
 */
bpmnModel(name = "TwoProcessesTwoDiagrams") {
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
    diagram(name = "Process B View", processId = "procB")
}
