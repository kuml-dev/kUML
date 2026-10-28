---
title: SysML 2 REQ – Anforderungen mit Gruppen
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - req
  - anforderungen
status: aktiv
---

# SysML 2 REQ — Anforderungen mit Gruppen, TestCase und sieben Beziehungen

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Welle C1 macht das Anforderungsdiagramm vollständig: Anforderungen tragen Priorität, Status, Quelle und Subject-Referenz, Annahmen werden gestrichelt gezeichnet, lange Titel umbrechen statt abzuschneiden, ein `testCaseDef` verifiziert, und Gruppen (bis Tiefe 3) fassen Anforderungen in Rahmen zusammen. Dazu kommen die Beziehungen `refine`, `trace` und `copy` neben `satisfy`, `verify`, `derive` und `contains`.

## Diagramm

```kuml
import dev.kuml.sysml2.RequirementPriority
import dev.kuml.sysml2.RequirementStatus
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Vehicle Requirements") {
    val car = partDef("Car")
    val brakes = partDef("BrakeSystem")
    val top = requirementDef(
        "TopSpeed",
        reqId = "R-001",
        text = "The vehicle shall reach at least 180 km/h on flat road within 30 seconds.",
        priority = RequirementPriority.HIGH,
        status = RequirementStatus.APPROVED,
        source = "Marketing, Product Brief 2026",
        subjectRef = "Car",
    )
    val stop = requirementDef(
        "StoppingDistanceFromHundredKilometresPerHourOnDryAndWetRoadSurfaces",
        reqId = "R-002",
        text = "The vehicle shall stop from 100 km/h within 36 metres on a dry road.",
        priority = RequirementPriority.MEDIUM,
        status = RequirementStatus.DRAFT,
    )
    val stopCopy = requirementDef(
        "StoppingCopy",
        text = "The vehicle shall stop from 100 km/h within 36 metres on a dry road.",
    )
    val dry = requirementDef(
        "DryRoadAssumption",
        text = "Tests are run on dry asphalt at 20 degrees Celsius.",
        isAssumption = true,
    )
    val abs = requirementDef("AbsRequired", text = "ABS is mandatory.", priority = RequirementPriority.LOW)
    val speedTest = testCaseDef("TopSpeedTest", text = "Accelerate on the test track.")
    val driving = useCaseDef("DriveFast")
    reqDiagram("Vehicle requirements") {
        include(car)
        include(brakes)
        include(driving)
        include(top)
        include(speedTest)
        group("Performance") {
            include(top)
            group("Braking") {
                include(stop)
                group("Details") {
                    include(stopCopy)
                    include(abs)
                }
            }
        }
        include(dry)
        satisfy(car, top)
        satisfy(brakes, stop)
        verify(speedTest, top)
        derive(abs, stop)
        contains(stop, abs)
        refine(driving, top)
        trace(dry, stop)
        copy(stopCopy, stop)
    }
}
```

## Was gezeigt wird

- `requirementDef(priority = …, status = …, source = …, isAssumption = true, subjectRef = …)`; `subjectRef` zeichnet zusätzlich eine «subject»-Kante.
- `testCaseDef(...)` als Verifikationselement (abgerundete Box, «testCase»).
- `group("Name") { … }` — verschachtelbar bis Tiefe 3, ein Element gehört höchstens einer Gruppe.
- `copy` verlangt identischen Text beider Anforderungen (Checker 202).
- `kuml validate` prüft die Regeln 201–217 (siehe Handbuch, Kapitel SysML 2).

Verwandt: [[08 SysML 2 REQ – Vehicle Requirements]]
