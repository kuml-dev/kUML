---
title: SysML 2 STM – Regionen mit Fork-Join
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - stm
  - zustandsmaschine
  - regionen
status: aktiv
---

# SysML 2 STM — Orthogonale Regionen mit Fork und Join

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Eine Bestellung wird **parallel** bezahlt und versandt. Der Zustand `Processing` hat zwei orthogonale Regionen (`Payment`, `Shipping`). Ein **Fork** betritt beide Regionen gleichzeitig, ein **Join** wartet, bis beide ihren Endzustand erreicht haben. Ab Welle A2 simuliert kUML das: pro Ereignis feuert höchstens ein Übergang je Region.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "BestellungParallel") {

    val initial = stateDef(name = "Initial", isInitial = true)
    val received = stateDef(name = "Received")
    val closed = stateDef(name = "Closed", isFinal = true)

    // ── Zusammengesetzter Zustand mit zwei Regionen ──────────────────────
    val processing = stateDef(name = "Processing")
    val payment = region(owner = processing, name = "Payment")
    val shipping = region(owner = processing, name = "Shipping")

    val payOpen = stateDef(name = "PaymentOpen", parent = processing, region = payment)
    val payDone = stateDef(name = "PaymentDone", parent = processing, region = payment)
    val shipPicking = stateDef(name = "Picking", parent = processing, region = shipping)
    val shipPacked = stateDef(name = "Packed", parent = processing, region = shipping)

    val split = fork(name = "Split")
    val merge = join(name = "Merge")

    // ── Übergänge ─────────────────────────────────────────────────────────
    transition(name = "init", source = initial, target = received)
    transition(name = "accept", source = received, target = split, trigger = "accept")
    transition(name = "toPayment", source = split, target = payOpen)
    transition(name = "toShipping", source = split, target = shipPicking)
    transition(name = "paid", source = payOpen, target = payDone, trigger = "paymentOk")
    transition(name = "packed", source = shipPicking, target = shipPacked, trigger = "packed")
    transition(name = "payJoin", source = payDone, target = merge, trigger = "complete")
    transition(name = "shipJoin", source = shipPacked, target = merge, trigger = "complete")
    transition(name = "finish", source = merge, target = closed, effect = "sendInvoice()")

    stmDiagram(name = "Bestellung parallel") {
        include(state = initial)
        include(state = received)
        include(state = processing)
        include(state = split)
        include(state = merge)
        include(state = closed)
    }
}
```

## Was gezeigt wird

- **Regionen** (`region(owner = …, name = …)`), die Kindzustände über `parent` und `region` zuordnen.
- **Fork**: genau eine eingehende, mindestens zwei ausgehende Kanten, Ziele in verschiedenen Regionen (Checker 103, 105).
- **Join**: wartet, bis alle Quellzustände aktiv sind; die Spur zeigt `JoinWaiting`, solange einer fehlt (Checker 104, 106).
- Ein Übergang direkt von einer Region in die andere ist ein Fehler (113) — dafür gibt es Fork und Join.

Verwandt: [[44 SysML 2 STM – History und Junction]]
