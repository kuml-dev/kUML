---
title: Service Blueprint – Notizen
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - blueprint
  - notiz
status: aktiv
---

# Service Blueprint – Notizen

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Notizen in einer Spalte rechts der Lanes mit gepunkteter Anker-Linie zur Schritt-Karte. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.Sentiment

blueprint(name = "Service") {
    val staff = actor(name = "Staff", role = ActorRole.STAFF)
    lateinit var fill: String
    lateinit var check: String
    phase(name = "Request") {
        fill = customer(name = "Fills in the form", sentiment = Sentiment.NEUTRAL)
        check = backstage(name = "Checks the data", actor = staff)
    }
    phase(name = "Admission") {
        customer(name = "Waits", sentiment = Sentiment.NEGATIVE)
    }
    note("ASSUMPTION A1: the form is digital", fill, check, id = "n1")
    note("Free-standing note", id = "free")
    blueprintDiagram(name = "Blueprint", emotionCurve = false)
}
```
