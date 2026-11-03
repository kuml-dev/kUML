---
title: BPMN – Formatierter Notiztext
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - bpmn
  - notiz
status: aktiv
---

# BPMN – Formatierter Notiztext

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Formatierte Textannotationen im BPMN-Prozess: Code, Kursivschrift, Liste und Link in der Annotation. (PAL-Welle N2)

## Diagramm

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Formatted notes") {
    process(id = "p", name = "Order") {
        val start = startEvent(name = "Start")
        val check = task(name = "Check order")
        val ship = task(name = "Ship order")
        val done = endEvent(name = "Done")
        sequenceFlow(from = start, to = check)
        sequenceFlow(from = check, to = ship)
        sequenceFlow(from = ship, to = done)
        textAnnotation(text = "**Hint:** call `validate()` first.\n- *stock*\n- credit", check, kind = NoteKind.HINT)
        textAnnotation(text = "Warning: see [the policy](https://example.org/policy).", ship, kind = NoteKind.WARNING)
    }
    diagram(name = "Formatted notes", processId = "p")
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `**Hint:**` und `` `validate()` `` | Fett und Code in der Annotation |
| `- ` Liste | Listenpunkte in der Klammer-Notiz |
| Link | Unterstrichener Text, wie in den anderen Notationen |
