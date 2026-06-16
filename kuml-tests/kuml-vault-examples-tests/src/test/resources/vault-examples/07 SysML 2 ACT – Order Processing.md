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

sysml2Model("OrderProcessing") {

    // ── PartDefinitions für `represents`-Targets ────────────────────────
    partDef("Customer")
    partDef("OrderSystem")
    partDef("Warehouse")

    // ── Activity Partitions (Swimlanes) ─────────────────────────────────
    val customerLane = activityPartition("Customer", represents = "Customer")
    val orderSysLane = activityPartition("OrderSystem", represents = "OrderSystem")
    val warehouseLane = activityPartition("Warehouse", represents = "Warehouse")

    // ── Customer Lane ───────────────────────────────────────────────────
    val initial = initialNode(partition = customerLane)
    val placeOrder = actionDef(
        "PlaceOrder",
        action = "submit(order)",
        partition = customerLane,
        pins = listOf(ActionPin("orderDetails", typeId = "Order", direction = PinDirection.Output)),
    )

    // ── OrderSystem Lane ────────────────────────────────────────────────
    val validate = actionDef(
        "ValidateOrder",
        action = "validate(order)",
        partition = orderSysLane,
        pins = listOf(
            ActionPin("orderDetails", typeId = "Order", direction = PinDirection.Input),
            ActionPin("validation", typeId = "Bool", direction = PinDirection.Output),
        ),
    )
    val decide = decisionNode("valid?", partition = orderSysLane)
    val pay = actionDef(
        "ProcessPayment",
        action = "charge(order.total)",
        partition = orderSysLane,
        pins = listOf(ActionPin("validation", typeId = "Bool", direction = PinDirection.Input)),
    )
    val cancel = actionDef(
        "CancelOrder",
        action = "notify(order, 'cancelled')",
        partition = orderSysLane,
        pins = listOf(ActionPin("validation", typeId = "Bool", direction = PinDirection.Input)),
    )

    // ── Warehouse Lane ──────────────────────────────────────────────────
    val reserve = actionDef(
        "ReserveInventory",
        action = "reserve(order.items)",
        partition = warehouseLane,
        pins = listOf(ActionPin("orderDetails", typeId = "Order", direction = PinDirection.Input)),
    )
    val ship = actionDef(
        "ShipOrder",
        action = "dispatch(order)",
        partition = warehouseLane,
        pins = listOf(ActionPin("inventory", typeId = "Inventory", direction = PinDirection.Input)),
    )
    val finalN = finalNode(partition = warehouseLane)
    val flowFinal = flowFinalNode(partition = warehouseLane)

    // ── Control Flows (token-passing edges) ─────────────────────────────
    controlFlow("start", initial, placeOrder)
    controlFlow("validated", validate, decide)
    controlFlow("yes", decide, pay, guard = "valid")
    controlFlow("payToReserve", pay, reserve)
    controlFlow("reserveToShip", reserve, ship)
    controlFlow("end", ship, finalN)
    controlFlow("no", decide, cancel, guard = "!valid")
    controlFlow("cancelEnd", cancel, flowFinal)

    // ── Object Flow (token carries a typed object) ──────────────────────
    objectFlow("carryOrder", placeOrder, validate, objectType = "Order")

    // ── Activity Diagram ────────────────────────────────────────────────
    actDiagram("Order Processing — workflow") {
        include(initial)
        include(placeOrder)
        include(validate)
        include(decide)
        include(pay)
        include(cancel)
        include(reserve)
        include(ship)
        include(finalN)
        include(flowFinal)
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
