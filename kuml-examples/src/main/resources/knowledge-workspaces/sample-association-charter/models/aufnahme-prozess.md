---
type: BpmnProcess
title: Aufnahmeverfahren
---

# Aufnahmeverfahren (Muster-Satzung, fiktives Beispiel)

> Teil des frei erfundenen Beispiel-Workspace „Muster-Verein für Offene
> Zusammenarbeit e.V." — siehe [Workspace-Index](../index.md).

BPMN-Prozess des Aufnahmeverfahrens nach [§2.1](../articles/02-mitgliedschaft.md):
vom Eingang des Aufnahmeantrags bis zur Entscheidung durch den
[Vorstand](../concepts/Vorstand.md). Bei einer Genehmigung startet der
[Mitgliedschafts-Lebenszyklus](mitgliedschaft-lebenszyklus.md).

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Aufnahmeverfahren") {
    process(id = "admission", name = "Aufnahmeverfahren") {
        val start = startEvent(name = "Aufnahmeantrag eingegangen")
        val pruefen = task(name = "Antrag pruefen", type = TaskType.USER)
        val vollstaendigGw = gateway(type = GatewayType.EXCLUSIVE, name = "Vollstaendig?")
        val nachfordern = task(name = "Unterlagen nachfordern", type = TaskType.SEND)
        val beschluss = task(name = "Vorstandsbeschluss", type = TaskType.USER)
        val genehmigtGw = gateway(type = GatewayType.EXCLUSIVE, name = "Genehmigt?")
        val aufnehmen = task(name = "Mitglied aufnehmen", type = TaskType.SERVICE)
        val ablehnen = task(name = "Ablehnung mitteilen", type = TaskType.SEND)
        val merge = gateway(type = GatewayType.EXCLUSIVE)
        val end = endEvent(name = "Verfahren abgeschlossen")

        sequenceFlow(from = start, to = pruefen)
        sequenceFlow(from = pruefen, to = vollstaendigGw)
        sequenceFlow(from = vollstaendigGw, to = beschluss, condition = "vollstaendig", name = "Ja")
        sequenceFlow(from = vollstaendigGw, to = nachfordern, condition = "unvollstaendig", name = "Nein", default = true)
        sequenceFlow(from = nachfordern, to = pruefen)
        sequenceFlow(from = beschluss, to = genehmigtGw)
        sequenceFlow(from = genehmigtGw, to = aufnehmen, condition = "genehmigt", name = "Ja")
        sequenceFlow(from = genehmigtGw, to = ablehnen, condition = "abgelehnt", name = "Nein", default = true)
        sequenceFlow(from = aufnehmen, to = merge)
        sequenceFlow(from = ablehnen, to = merge)
        sequenceFlow(from = merge, to = end)
    }
    diagram(name = "Aufnahmeverfahren", processId = "admission")
}
```
