---
tags: [kUML, BPMN, beispiel]
status: aktiv
date: 2026-06-30
---

# BPMN Choreography – Bestellprozess

Choreographie-Sicht auf einen Bestellprozess: die Reihenfolge der Nachrichtenaustausche zwischen Kunde, Händler und Lieferant — ohne interne Prozesslogik der einzelnen Parteien.

> [!note] Choreography vs. Collaboration
> Ein Choreography Diagram zeigt die **Reihenfolge der Interaktionen** zwischen Parteien als Sequenz von Choreography-Tasks (jeder Task = ein Nachrichtenaustausch zwischen genau zwei Participants). Es gibt keine Pools und keine internen Prozesse — nur die vereinbarte Abfolge des Nachrichtenflusses. Für interne Prozessdetails: [[32 BPMN Collaboration – Customer und Supplier|Collaboration Diagram]].

> [!tip] DSL-Schreibweise
> - `task(name, initiatingParticipant, participants = arrayOf("A", "B")) { … }` — ein Choreography-Task zwischen genau zwei Participants; der initiierende oben (gelbes Band).
> - `message(name, participantRef, isInitiating)` — Nachrichtenband am Task (initiierend = weiß, Antwort = grau).
> - `gateway(type = GatewayType.EXCLUSIVE, name)` — verzweigt den Choreographie-Fluss.
> - `sequenceFlow(from, to, condition)` — verbindet Choreographie-Elemente.

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Bestellprozess Choreographie") {
    val chId = choreography(id = "order_choreo", name = "Bestellung") {
        val start = startEvent(name = "Bedarf erkannt")

        val bestellung = task(
            name = "Bestellung aufgeben",
            initiatingParticipant = "Kunde",
            participants = arrayOf("Kunde", "Händler"),
        ) {
            message(name = "Bestellanfrage", participantRef = "Kunde", isInitiating = true)
            message(name = "Auftragsbestätigung", participantRef = "Händler", isInitiating = false)
        }

        val gw = gateway(type = GatewayType.EXCLUSIVE, name = "Auf Lager?")

        val nachbestellung = task(
            name = "Nachbestellen",
            initiatingParticipant = "Händler",
            participants = arrayOf("Händler", "Lieferant"),
        ) {
            message(name = "Nachbestellung", participantRef = "Händler", isInitiating = true)
            message(name = "Lieferzusage", participantRef = "Lieferant", isInitiating = false)
        }

        val lieferung = task(
            name = "Lieferung",
            initiatingParticipant = "Händler",
            participants = arrayOf("Händler", "Kunde"),
        ) {
            message(name = "Versandbenachrichtigung", participantRef = "Händler", isInitiating = true)
        }

        val end = endEvent(name = "Bestellung abgeschlossen")

        sequenceFlow(from = start, to = bestellung)
        sequenceFlow(from = bestellung, to = gw)
        sequenceFlow(from = gw, to = lieferung, condition = "auf Lager", name = "Ja")
        sequenceFlow(from = gw, to = nachbestellung, condition = "nicht auf Lager", name = "Nein")
        sequenceFlow(from = nachbestellung, to = lieferung)
        sequenceFlow(from = lieferung, to = end)
    }

    choreographyDiagram("Bestellprozess-Choreographie", choreographyId = chId)
}
```
