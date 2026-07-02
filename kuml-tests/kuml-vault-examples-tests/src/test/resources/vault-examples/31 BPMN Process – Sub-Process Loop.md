---
tags: [kUML, BPMN, beispiel]
status: aktiv
date: 2026-06-22
---

# BPMN Process – Sub-Process mit Loop

Review-Prozess mit expandiertem Sub-Process, Timer-Boundary-Event und einer parallelen Mehrfach-Instanz-Aufgabe (mehrere Gutachter gleichzeitig). Zusätzlich ein **transaktionaler Sub-Process** für die anschließende Freigabe-Buchung (Compensation-Semantik: alles oder nichts).

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Document Review") {
    process(id = "p_review", name = "Review Process") {
        val start  = startEvent(name = "Document Submitted")
        val review = subProcess(name = "Review Cycle", expanded = true) {
            val rStart  = startEvent(name = "Start Review")
            // Mehrere Gutachter parallel — Multi-Instance-Marker (‖) statt Loop-Marker
            val read    = task(name = "Read Document", type = TaskType.USER) {
                multiInstance(sequential = false)
            }
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

        // Transaktionaler Sub-Process: Buchung der Freigabe — bei Fehler wird alles zurückgerollt
        val booking = subProcess(name = "Record Approval", expanded = true, transactional = true) {
            val bStart = startEvent(name = "Start Booking")
            val book   = task(name = "Book Approval", type = TaskType.SERVICE)
            val bEnd   = endEvent(name = "Booking Complete")
            sequenceFlow(from = bStart, to = book)
            sequenceFlow(from = book, to = bEnd)
        }

        val done    = endEvent(name = "Review Complete")
        sequenceFlow(from = start, to = review)
        sequenceFlow(from = review, to = booking)
        sequenceFlow(from = booking, to = done)
        sequenceFlow(from = timer, to = expired)

        // Event-Sub-Process: wird nur durch ein Start-Event ausgelöst, läuft nicht im Hauptfluss
        subProcess(name = "Handle Escalation", expanded = true, triggeredByEvent = true) {
            val escalationStart = startEvent(name = "Escalation Raised", definition = EventDefinition.ESCALATION)
            val notifyManager    = task(name = "Notify Manager", type = TaskType.SEND)
            val escalationEnd    = endEvent(name = "Escalation Handled")
            sequenceFlow(from = escalationStart, to = notifyManager)
            sequenceFlow(from = notifyManager, to = escalationEnd)
        }
    }
    diagram(name = "Document Review", processId = "p_review")
}
```

## DSL-Anatomie (Ergänzung)

| Element | Bedeutung |
|---|---|
| `task(...) { multiInstance(sequential = false) }` | Mehrfach-Instanz-Marker (‖ bei parallel, ≡ bei `sequential = true`) — die Aktivität wird für eine Menge von Elementen mehrfach ausgeführt. |
| `subProcess(..., transactional = true)` | Transaktionaler Sub-Process — doppelter Rahmen im Rendering; alle Aktivitäten gelten als atomare Einheit (Compensation bei Fehlschlag). |
| `subProcess(..., triggeredByEvent = true)` | Event-Sub-Process — hängt lose am umgebenden Prozess, wird nur durch sein eigenes Start-Event ausgelöst (hier: Eskalation), nicht durch den regulären Sequenzfluss. |
