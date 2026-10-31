---
title: SysML 2 BDD – sameLayer mit Id-Strings
date: 2026-10-31
tags:
  - kUML
  - beispiel
  - sysml2
  - bdd
  - layout
status: aktiv
---

# SysML 2 BDD – sameLayer mit Id-Strings

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> `sameLayer`, `first` und `last` nehmen Elemente oder Id-Strings. Hier steht `Vehicle` ganz oben, `Engine` und `Battery` in derselben Schicht. (PAL-Welle 2B-b)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "VehicleStructure") {
    val vehicle = partDef(name = "Vehicle")
    val engine = partDef(name = "Engine")
    val battery = partDef(name = "Battery")
    val ecu = partDef(name = "ECU")
    val sensor = partDef(name = "Sensor")

    bdd(name = "Vehicle structure") {
        include(definition = vehicle)
        include(definition = engine)
        include(definition = battery)
        include(definition = ecu)
        include(definition = sensor)
        layout {
            first(vehicle.id)
            sameLayer(engine.id, battery.id)
            last("ghost")
        }
    }
}
```

## Hinweis

Die Id `ghost` gibt es nicht im Diagramm: `kuml validate` und `kuml render` melden `KUML-LAYOUT-W004` (unbekannte Id) und ignorieren sie.
