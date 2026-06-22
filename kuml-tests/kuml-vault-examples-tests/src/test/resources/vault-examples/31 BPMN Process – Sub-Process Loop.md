---
tags: [kUML, BPMN, beispiel]
status: aktiv
date: 2026-06-22
---

# BPMN Process – Sub-Process mit Loop

Review-Prozess mit expandiertem Sub-Process und Timer-Boundary-Event.

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Document Review") {
    process(id = "p_review", name = "Review Process") {
        val start  = startEvent(name = "Document Submitted")
        val review = subProcess(name = "Review Cycle", expanded = true) {
            val rStart  = startEvent(name = "Start Review")
            val read    = task(name = "Read Document", type = TaskType.USER)
            val decide  = gateway(type = GatewayType.EXCLUSIVE, name = "OK?")
            val approve = endEvent(name = "Approved")
            val revise  = task(name = "Request Revision", type = TaskType.USER)
            val rEnd    = endEvent(name = "Revision Requested")
            sequenceFlow(from = rStart, to = read)
            sequenceFlow(from = read, to = decide)
            sequenceFlow(from = decide, to = approve, condition = "approved")
            sequenceFlow(from = decide, to = revise, condition = "needs_revision")
            sequenceFlow(from = revise, to = rEnd)
        }
        val timer   = boundaryEvent(attachedTo = review, name = "Deadline", definition = EventDefinition.TIMER)
        val expired = endEvent(name = "Review Expired", definition = EventDefinition.TERMINATE)
        val done    = endEvent(name = "Review Complete")
        sequenceFlow(from = start, to = review)
        sequenceFlow(from = review, to = done)
        sequenceFlow(from = timer, to = expired)
    }
    diagram(name = "Document Review", processId = "p_review")
}
```
