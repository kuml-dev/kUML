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

bpmnModel("Document Review") {
    process(id = "p_review", name = "Review Process") {
        val start  = startEvent("Document Submitted")
        val review = subProcess(name = "Review Cycle", expanded = true) {
            val rStart  = startEvent("Start Review")
            val read    = task("Read Document", TaskType.USER)
            val decide  = gateway(GatewayType.EXCLUSIVE, "OK?")
            val approve = endEvent("Approved")
            val revise  = task("Request Revision", TaskType.USER)
            val rEnd    = endEvent("Revision Requested")
            sequenceFlow(rStart, read)
            sequenceFlow(read, decide)
            sequenceFlow(decide, approve, condition = "approved")
            sequenceFlow(decide, revise, condition = "needs_revision")
            sequenceFlow(revise, rEnd)
        }
        val timer   = boundaryEvent(review, name = "Deadline", definition = EventDefinition.TIMER)
        val expired = endEvent("Review Expired", EventDefinition.TERMINATE)
        val done    = endEvent("Review Complete")
        sequenceFlow(start, review)
        sequenceFlow(review, done)
        sequenceFlow(timer, expired)
    }
    diagram("Document Review", "p_review")
}
```
