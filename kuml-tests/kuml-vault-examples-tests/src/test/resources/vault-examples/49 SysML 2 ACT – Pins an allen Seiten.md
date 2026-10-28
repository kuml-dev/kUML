---
title: SysML 2 ACT – Pins an allen Seiten
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - activity
status: aktiv
---

# SysML 2 Activity Diagram — Pins an allen Seiten

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> `ActionPin(side = PinSide.Top|Bottom|Left|Right)` setzt Pins an eine beliebige Seite; Objektflüsse mit `sourcePin`/`targetPin` enden direkt am Pin-Quadrat. Ohne `side` bleibt es bei Eingang links, Ausgang rechts.

## Diagramm

```kuml
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Pins") {
    val make = actionDef(
        name = "MakeParts",
        pins = listOf(
            ActionPin(name = "parts", typeId = "Part", direction = PinDirection.Output, side = PinSide.Bottom),
            ActionPin(name = "scrap", typeId = "Part", direction = PinDirection.Output, side = PinSide.Right),
        ),
    )
    val assemble = actionDef(
        name = "Assemble",
        pins = listOf(ActionPin(name = "parts", typeId = "Part", direction = PinDirection.Input, side = PinSide.Top)),
    )
    val recycle = actionDef(
        name = "Recycle",
        pins = listOf(ActionPin(name = "scrap", typeId = "Part", direction = PinDirection.Input, side = PinSide.Left)),
    )
    objectFlow(name = "good", source = make, target = assemble, objectType = "Part", sourcePin = "parts", targetPin = "parts")
    objectFlow(name = "bad", source = make, target = recycle, objectType = "Part", sourcePin = "scrap", targetPin = "scrap")
    actDiagram("Pins an allen Seiten") {
        include(make)
        include(assemble)
        include(recycle)
    }
}
```
