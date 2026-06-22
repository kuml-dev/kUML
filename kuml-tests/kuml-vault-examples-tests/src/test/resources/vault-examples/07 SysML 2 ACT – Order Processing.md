---
title: SysML 2 ACT – Order Processing
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - sysml2
  - activity
  - workflow
status: aktiv
---

# SysML 2 Activity Diagram — Order Processing

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Komplexes **Activity-Diagramm** mit Swimlanes (Partitions), Entscheidung, Cancellation-Branch und typisierten Action-Pins. Modelliert einen Bestellprozess von der Aufgabe bis zur Auslieferung — inklusive Fehlerpfad bei ungültiger Bestellung.

## Diagramm

```kuml
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "OrderProcessing") {

    // ── PartDefinitions für `represents`-Targets ────────────────────────
    partDef(name = "Customer")
    partDef(name = "OrderSystem")
    partDef(name = "Warehouse")

    // ── Activity Partitions (Swimlanes) ─────────────────────────────────
    val customerLane = activityPartition(name = "Customer", represents = "Customer")
    val orderSysLane = activityPartition(name = "OrderSystem", represents = "OrderSystem")
    val warehouseLane = activityPartition(name = "Warehouse", represents = "Warehouse")

    // ── Customer Lane ───────────────────────────────────────────────────
    val initial = initialNode(partition = customerLane)
    val placeOrder = actionDef(
        name = "PlaceOrder",
        action = "submit(order)",
        partition = customerLane,
        pins = listOf(ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Output)),
    )

    // ── OrderSystem Lane ────────────────────────────────────────────────
    val validate = actionDef(
        name = "ValidateOrder",
        action = "validate(order)",
        partition = orderSysLane,
        pins = listOf(
            ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Input),
            ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Output),
        ),
    )
    val decide = decisionNode(name = "valid?", partition = orderSysLane)
    val pay = actionDef(
        name = "ProcessPayment",
        action = "charge(order.total)",
        partition = orderSysLane,
        pins = listOf(ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Input)),
    )
    val cancel = actionDef(
        name = "CancelOrder",
        action = "notify(order, 'cancelled')",
        partition = orderSysLane,
        pins = listOf(ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Input)),
    )

    // ── Warehouse Lane ──────────────────────────────────────────────────
    val reserve = actionDef(
        name = "ReserveInventory",
        action = "reserve(order.items)",
        partition = warehouseLane,
        pins = listOf(ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Input)),
    )
    val ship = actionDef(
        name = "ShipOrder",
        action = "dispatch(order)",
        partition = warehouseLane,
        pins = listOf(ActionPin(name = "inventory", typeId = "Inventory", direction = PinDirection.Input)),
    )
    val finalN = finalNode(partition = warehouseLane)
    val flowFinal = flowFinalNode(partition = warehouseLane)

    // ── Control Flows (token-passing edges) ─────────────────────────────
    controlFlow(name = "start", source = initial, target = placeOrder)
    controlFlow(name = "validated", source = validate, target = decide)
    controlFlow(name = "yes", source = decide, target = pay, guard = "valid")
    controlFlow(name = "payToReserve", source = pay, target = reserve)
    controlFlow(name = "reserveToShip", source = reserve, target = ship)
    controlFlow(name = "end", source = ship, target = finalN)
    controlFlow(name = "no", source = decide, target = cancel, guard = "!valid")
    controlFlow(name = "cancelEnd", source = cancel, target = flowFinal)

    // ── Object Flow (token carries a typed object) ──────────────────────
    objectFlow(name = "carryOrder", source = placeOrder, target = validate, objectType = "Order")

    // ── Activity Diagram ────────────────────────────────────────────────
    actDiagram(name = "Order Processing — workflow") {
        include(node = initial)
        include(node = placeOrder)
        include(node = validate)
        include(node = decide)
        include(node = pay)
        include(node = cancel)
        include(node = reserve)
        include(node = ship)
        include(node = finalN)
        include(node = flowFinal)
    }
}
```

## Die 8 Activity-Node-Kinds

| Kind | DSL-Funktion | Form | Wofür |
|---|---|---|---|
| **Initial** | `initialNode()` | Gefüllter Kreis | Startpunkt des Workflows |
| **Final** | `finalNode()` | Donut | Erfolgreicher Endpunkt — terminiert die ganze Aktivität |
| **FlowFinal** | `flowFinalNode()` | Kreis mit X | Beendet *diesen* Token — Aktivität läuft weiter |
| **Action** | `actionDef(name, action = …)` | Abgerundetes Rechteck | Atomare Arbeitseinheit |
| **Decision** | `decisionNode("valid?")` | Diamant | Verzweigung — Token folgt Branch mit erfüllten Guard |
| **Merge** | `mergeNode()` | Diamant | Zusammenführen alternativer Pfade (kein Sync!) |
| **Fork** | `forkNode()` | Schmale dicke Bar | Spaltet Token in parallele Branches |
| **Join** | `joinNode()` | Schmale dicke Bar | Synchronisiert parallele Branches |

## Activity Partitions (Swimlanes)

`activityPartition("Customer", represents = "Customer")` erzeugt eine vertikale **Swimlane**, die Aktionen nach ausführender Entität gruppiert. Jede Action gibt `partition = customerLane` an. Das macht **Verantwortlichkeiten visuell** klar — wer macht was im Prozess.

## Control Flow vs. Object Flow

| Flow-Typ | DSL | Trägt |
|---|---|---|
| **ControlFlow** | `controlFlow(name, source, target, guard = ?)` | Nur Token (Kontrolle) — kann durch Guard gefiltert werden |
| **ObjectFlow** | `objectFlow(name, source, target, objectType = …)` | Typisiertes Datum von Output-Pin zu Input-Pin |

## Action Pins

`ActionPin(name, typeId, direction)` deklariert typisierte Ein-/Ausgänge einer Action. Wie bei einem Funktions-Signature in Code:

- `PinDirection.Input` — Action konsumiert ein Datum
- `PinDirection.Output` — Action produziert ein Datum

ObjectFlows können Output-Pins mit Input-Pins verbinden — heute aber nur konzeptuell, Pin-Anchoring im Renderer ist V2.x.

## Live-Simulation

Wie auch das STM-Beispiel ist dieses Modell **ausführbar** via `kuml simulate`. Token wandern deterministisch durch die Decision-Branches, je nach `valid`-Wert im Event-Context. Siehe V2.0.18 in [[02 Projekte/kUML V2.0]].

## Verwandte Beispiele

- [[04 SysML 2 STM – Traffic Light]] — Behaviour als Zustandsmaschine statt als Token-Flow
- [[03 SysML 2 BDD – Hybrid Vehicle]] — strukturelles Modell statt Verhalten

## Verwandte Vault-Notizen

- [[03 Bereiche/kUML/ADR/ADR-0007 Executable Behaviour Runtime]]
- [[02 Projekte/kUML V2.0#Executable Behaviour Runtime (Vollausbau)]]
