---
title: Service Blueprint – Layout-Block (Determinismus)
date: 2026-10-31
tags:
  - kUML
  - beispiel
  - blueprint
  - layout
status: aktiv
---

# Service Blueprint – Layout-Block (Determinismus)

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Das Service Blueprint ist ein festes Raster. Ein `layout { }`-Block wird daher nicht angewendet: `KUML-LAYOUT-W001` meldet jede gesetzte Option einmal, die Zeichnung bleibt bei zwei Läufen bytegleich. (PAL-Welle 2B-b)

## Diagramm

```kuml
import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.Sentiment

blueprint(name = "Service") {
    val staff = actor(name = "Staff", role = ActorRole.STAFF)
    phase(name = "Request") {
        customer(name = "Fills in the form", sentiment = Sentiment.NEUTRAL)
        backstage(name = "Checks the data", actor = staff)
    }
    phase(name = "Admission") {
        customer(name = "Waits", sentiment = Sentiment.NEGATIVE)
    }
    blueprintDiagram(name = "Blueprint", emotionCurve = false) {
        layout {
            direction = Right
            wrapColumns = 2
        }
    }
}
```
