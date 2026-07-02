---
title: SysML 2 BDD – Hybrid Vehicle
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - sysml2
  - bdd
status: aktiv
---

# SysML 2 Block Definition Diagram — Hybrid Vehicle

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Minimal-Beispiel für ein **SysML 2 BDD** (Block Definition Diagram) — das SysML-2-Analogon zum UML-Klassendiagramm, aber für **System Engineering**: physische Komponenten, Attribute mit Typen, Spezifikation eines Fahrzeugs. Zeigt zusätzlich **Spezialisierung** (`specializesId`) und **abstrakte Definitionen** (`isAbstract`) — das SysML-2-Pendant zur UML-Generalisierung.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "VehicleSystem") {
    val massType = attributeDef(name = "Mass")
    val powerType = attributeDef(name = "Power")

    // Abstrakte Basis-Definition — kann nicht direkt instanziiert werden
    val powertrain = partDef(name = "Powertrain", isAbstract = true) {
        attribute(name = "ratedPower", typeId = powerType.id)
    }

    val vehicle = partDef(name = "Vehicle") {
        attribute(name = "mass", typeId = massType.id)
    }

    // Spezialisierung: Engine ist eine konkrete Powertrain-Ausprägung
    val engine = partDef(name = "Engine", specializesId = powertrain.id) {
        attribute(name = "ratedPower", typeId = powerType.id)
    }

    bdd(name = "Vehicle BDD") {
        include(definition = powertrain)
        include(definition = vehicle)
        include(definition = engine)
    }
}
```

## SysML-2-Besonderheiten

Anders als UML-Klassen baut SysML 2 auf **KerML** auf und kennt eine fundamentale Dualität:

| Konzept | Bedeutung |
|---|---|
| **Definition** | "Typ-Ebene" — *was kann existieren*. `partDef`, `attributeDef`, `portDef`, … |
| **Usage** | "Instanz-Ebene" — *was existiert konkret in diesem Kontext*. `partUsage`, `attributeUsage`, … |

In diesem Beispiel sehen wir nur die **Definition-Seite** (das System-Vokabular). Sobald wir konkrete Konfigurationen modellieren (z. B. *ein* spezifisches Hybridfahrzeug mit *einem* spezifischen Motor), kommen Usages dazu — siehe [[03 Bereiche/kUML/ADR/ADR-0003 SysML 2 als eigenständiges Metamodell]].

## DSL-Anatomie

| Zeile | Bedeutung |
|---|---|
| `import dev.kuml.sysml2.dsl.sysml2Model` | Expliziter Import — SysML 2 ist ein eigenes Modul. |
| `sysml2Model(name = "VehicleSystem") { … }` | Top-Level für ein SysML-2-Modell. |
| `val massType = attributeDef(name = "Mass")` | Attribut-Definition als wiederverwendbarer Typ. |
| `partDef(name = "Vehicle") { attribute(name = "mass", typeId = massType.id) }` | Part-Definition mit typisiertem Attribut — `typeId` referenziert die `attributeDef`. |
| `partDef(name = "Powertrain", isAbstract = true) { … }` | Abstrakte Definition — kann nicht direkt usiert werden, nur über Spezialisierungen. |
| `partDef(name = "Engine", specializesId = powertrain.id) { … }` | Spezialisierung — SysML-2-Pendant zur UML-`generalization`; `Engine` erbt die Merkmale von `Powertrain`. |
| `bdd("Vehicle BDD") { include(vehicle); include(engine) }` | Block Definition Diagram, das die Parts visuell darstellt. |

## Wann SysML 2 statt UML?

| Verwende **UML**, wenn… | Verwende **SysML 2**, wenn… |
|---|---|
| Du Software modellierst (Klassen, Komponenten) | Du physische Systeme modellierst (Fahrzeuge, Sensoren, Mechanik) |
| Du Quelltext-nahe Modelle willst | Du Engineering-Constraints (Einheiten, Mengenlehre, Anforderungen) brauchst |
| Du C4 oder Domain-Driven-Design fährst | Du AUTOSAR, MBSE oder System Engineering fährst |
| Klassendiagramme genügen | Du `bdd`, `ibd`, `par`, `req`, `act`, `seq`, `stm`, `uc` brauchst |

## Verwandte Beispiele

- [[04 SysML 2 STM – Traffic Light]] — Behaviour-Modell statt strukturell
- [[05 SysML 2 UC – Library System]] — Akteure und Use-Cases in SysML 2
- [[06 SysML 2 SEQ – Login Flow]] — Sequence-Diagramm in SysML 2

## Verwandte Vault-Notizen

- [[03 Bereiche/kUML/ADR/ADR-0003 SysML 2 als eigenständiges Metamodell]]
- [[02 Projekte/kUML V2.0#SysML-2-Diagrammtyp-Serie ist KOMPLETT (8/8)]]
