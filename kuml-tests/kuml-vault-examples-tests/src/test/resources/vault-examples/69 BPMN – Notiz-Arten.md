---
title: BPMN – Notiz-Arten
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - bpmn
  - notiz
status: aktiv
---

# BPMN – Notiz-Arten

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Textannotationen mit Art im BPMN-Prozess. Statt des Streifens zeigt BPMN die Klammer der Annotation in der Farbe der Art. (PAL-Welle N1)

## Diagramm

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Note kinds") {
    process(id = "p", name = "Order") {
        val start = startEvent(name = "Start")
        val check = task(name = "Check order")
        val ship = task(name = "Ship order")
        val done = endEvent(name = "Done")
        sequenceFlow(from = start, to = check)
        sequenceFlow(from = check, to = ship)
        sequenceFlow(from = ship, to = done)
        textAnnotation(text = "A plain note without a symbol.", start, kind = NoteKind.NOTE)
        textAnnotation(text = "Hint: check the stock first.", check, kind = NoteKind.HINT)
        textAnnotation(text = "Risk: the carrier may be late.", ship, kind = NoteKind.RISK)
        textAnnotation(text = "Success: the order is complete.", done, kind = NoteKind.SUCCESS)
    }
    diagram(name = "Note kinds", processId = "p")
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `textAnnotation(text, element, kind = NoteKind.X)` | Annotation mit Art |
| Farbige Klammer | BPMN-typische Klammer statt Streifen, mit Symbol vor dem Text |
| `NOTE` | Die einfache Annotation bleibt schlicht |
