---
title: SysML 2 IBD – Hybrid Vehicle Wiring
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - sysml2
  - ibd
status: aktiv
---

# SysML 2 Internal Block Diagram — Hybrid Vehicle Wiring

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **SysML 2 IBD** (Internal Block Diagram) zeigt die *Verdrahtung im Inneren* eines Blocks — Part-Usages, Ports und Connections. Hier: die Innensicht eines `HybridVehicle` mit `ElectricMotor`, `InternalCombustionEngine`, `Battery` und `PowerSplitter`, verbunden über Power-Lines und Driveshafts.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "HybridVehicleSystem") {
    // Connection-Typen für die Verdrahtung
    val powerLine  = connectionDef(name = "PowerLine")
    val driveshaft = connectionDef(name = "Driveshaft")

    // Port-Typen — jeder port(...) braucht eine typeId-Referenz
    val dcPort    = portDef(name = "DcPort")
    val shaftPort = portDef(name = "ShaftPort")

    val battery = partDef(name = "Battery") {
        port(name = "dcOut", typeId = dcPort.id)
    }
    val electricMotor = partDef(name = "ElectricMotor") {
        port(name = "dcIn",  typeId = dcPort.id)
        port(name = "shaft", typeId = shaftPort.id)
    }
    val iceEngine = partDef(name = "InternalCombustionEngine") {
        port(name = "shaft", typeId = shaftPort.id)
    }
    val powerSplitter = partDef(name = "PowerSplitter") {
        port(name = "emIn",  typeId = shaftPort.id)
        port(name = "iceIn", typeId = shaftPort.id)
    }

    val hybrid = partDef(name = "HybridVehicle") {
        part(name = "battery",       typeId = battery.id)
        part(name = "electricMotor", typeId = electricMotor.id)
        part(name = "iceEngine",     typeId = iceEngine.id)
        part(name = "powerSplitter", typeId = powerSplitter.id)

        connect(
            name = "batteryToMotor",
            typeId = powerLine.id,
            sourceEndId = "HybridVehicle::battery::dcOut",
            targetEndId = "HybridVehicle::electricMotor::dcIn",
        )
        connect(
            name = "motorToSplitter",
            typeId = driveshaft.id,
            sourceEndId = "HybridVehicle::electricMotor::shaft",
            targetEndId = "HybridVehicle::powerSplitter::emIn",
        )
        connect(
            name = "iceToSplitter",
            typeId = driveshaft.id,
            sourceEndId = "HybridVehicle::iceEngine::shaft",
            targetEndId = "HybridVehicle::powerSplitter::iceIn",
        )
    }

    ibd(name = "HybridVehicle — internal block diagram", owner = hybrid)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `connectionDef(name = "PowerLine")` | Typ-Definition für Verbindungen — referenzierbar in `connect(typeId = …)`. |
| `portDef(name = "DcPort")` | Typ-Definition für Ports — jeder `port(…)` braucht eine `typeId`-Referenz darauf. |
| `partDef(name = "Battery") { port(name = â¦, typeId = …) }` | Block-Definition mit typisierten Ports. |
| `partDef(name = "HybridVehicle") { part(…); connect(…) }` | Composite-Block mit Part-Usages und internen Verbindungen. |
| `part("battery", typeId = battery.id)` | Part-Usage: Rollenname + Typ-Referenz (über `.id` der `partDef`). |
| `connect(name = …, typeId = …, sourceEndId = …, targetEndId = …)` | Connection auf Modell-Ebene. Endpoints adressieren die **Part-Usage-Pfade** im Owner (`HybridVehicle::battery::dcOut`), nicht die Ports der `partDef`. Bridge: Longest-Prefix-Match gegen die sichtbaren Part-Usage-IDs. |
| `ibd("...", owner = hybrid)` | Erzeugt das IBD mit `owner` als Container — die Part-Usages und Connections von `hybrid` werden gerendert. |

## BDD vs. IBD

| BDD | IBD |
|---|---|
| Definitions, Vererbung, Komposition | Konkrete Verdrahtung im Inneren eines Blocks |
| „Welche Blocks gibt es?" | „Wie sind sie miteinander verbunden?" |
| UML-Klassendiagramm-Analogon | UML-Composite-Structure-Analogon |

## Mögliche Erweiterungen

- **Spezialisierte Connections**: zusätzliche `connectionDef` für Daten- vs. Power-Lines
- **Geschachtelte IBDs**: ein zweites `ibd` mit `owner = electricMotor` für die Motor-Internals
- **Parametric Constraints**: F=m·a über die Power-Flow-Connections — siehe [[28 SysML 2 PAR – Newton]]

## Verwandte Beispiele

- [[03 SysML 2 BDD – Hybrid Vehicle]] — Definitionssicht (BDD)
- [[13 UML Composite Structure – Order Internals]] — UML-Analogon
- [[28 SysML 2 PAR – Newton]] — Parametric-Constraints am Vehicle
