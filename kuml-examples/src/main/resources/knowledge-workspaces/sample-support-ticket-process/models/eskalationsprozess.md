---
type: BpmnProcess
title: Eskalationsprozess
---

# Eskalationsprozess (Muster-Firma, fiktives Beispiel)

> Teil des frei erfundenen Beispiel-Workspace „Support-Ticket-Prozess der
> Muster-Firma GmbH" — siehe [Workspace-Index](../index.md).

BPMN-Prozess der Eskalation nach [§2.2](../articles/02-eskalationsregeln.md):
von der automatischen Fristüberschreitungs-Erkennung bis zur Übernahme
durch einen Senior-[Supportmitarbeiter](../concepts/Supportmitarbeiter.md)
und der Benachrichtigung des [Kunden](../concepts/Kunde.md).

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Eskalationsprozess") {
    process(id = "escalation", name = "Eskalationsprozess") {
        val start = startEvent(name = "Frist ueberschritten")
        val pruefen = task(name = "Verfuegbaren Senior pruefen", type = TaskType.SERVICE)
        val verfuegbarGw = gateway(type = GatewayType.EXCLUSIVE, name = "Senior verfuegbar?")
        val uebernehmen = task(name = "Ticket uebernehmen", type = TaskType.USER)
        val warten = task(name = "In Warteschlange stellen", type = TaskType.SEND)
        val benachrichtigen = task(name = "Kunde benachrichtigen", type = TaskType.SEND)
        val end = endEvent(name = "Eskalation eingeleitet")

        sequenceFlow(from = start, to = pruefen)
        sequenceFlow(from = pruefen, to = verfuegbarGw)
        sequenceFlow(from = verfuegbarGw, to = uebernehmen, condition = "verfuegbar", name = "Ja")
        sequenceFlow(from = verfuegbarGw, to = warten, condition = "nichtVerfuegbar", name = "Nein", default = true)
        sequenceFlow(from = uebernehmen, to = benachrichtigen)
        sequenceFlow(from = warten, to = benachrichtigen)
        sequenceFlow(from = benachrichtigen, to = end)
    }
    diagram(name = "Eskalationsprozess", processId = "escalation")
}
```
