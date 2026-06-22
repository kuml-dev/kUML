---
title: UML State Machine – Order Lifecycle
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - state-machine
status: aktiv
---

# UML Zustandsdiagramm — Order Lifecycle

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Zustandsdiagramm** (State Machine Diagram) zeigt, *wie sich ein Objekt über die Zeit verändert*. Hier: Der Lebenszyklus einer Bestellung von `Draft` über `Confirmed` und `Processing` (zusammengesetzter Zustand mit Substates `Picking` und `Packing`) bis `Shipped` — inklusive Abbruch-Pfad.

## Diagramm

```kuml
stateDiagram(name = "Order Lifecycle") {
    val start = initialState()
    val draft = state(name = "Draft") {
        entry = "validate()"
    }
    val confirmed = state(name = "Confirmed")
    val processing = compositeState(name = "Processing") {
        state(name = "Picking")
        state(name = "Packing")
    }
    val shipped = state(name = "Shipped")
    val cancelled = finalState(name = "Cancelled")
    val done = finalState(name = "Done")

    transition(source = start, target = draft)
    transition(source = draft, target = confirmed) {
        trigger = "confirm()"
        guard = "[valid]"
    }
    transition(source = draft, target = cancelled) {
        trigger = "cancel()"
    }
    transition(source = confirmed, target = processing) {
        trigger = "process()"
    }
    transition(source = processing, target = shipped) {
        trigger = "ship()"
        effect = "notifyCustomer()"
    }
    transition(source = shipped, target = done)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `stateDiagram(name = …) { … }` | Top-Level: erzeugt ein UML-Zustandsdiagramm; die enthaltene State Machine trägt denselben Namen. |
| `initialState()` / `finalState(name = …)` | Pseudo-State **INITIAL** / FinalState. |
| `state(name = …) { entry = …; exit = …; doActivity = … }` | Einfacher Zustand mit optionalen Verhaltensaktionen. |
| `compositeState(name = …) { state(…); state(…) }` | Geschachtelter Zustand mit Substates. |
| `transition(source, target) { trigger = …; guard = …; effect = … }` | Zustandsübergang mit Auslöser, Bedingung und Effekt. |
| `choice(name = …)` / `fork()` / `join()` / `junction()` | Weitere Pseudo-States. |

## Mögliche Erweiterungen

- **Choice-State**: `val ok = choice("PaymentOK?")` für bedingte Verzweigung
- **History-State**: in `compositeState` über Pseudo-State `H` (in DSL als History erweiterbar)
- **Entry/Exit/Do**: `state(name = "Confirmed") { entry = "lockInventory()"; exit = "releaseInventory()" }`

## Verwandte Beispiele

- [[04 SysML 2 STM – Traffic Light]] — SysML-2-Pendant
- [[17 UML Activity – Checkout Flow]] — Ablauf statt Zustände
- [[21 UML Timing – TCP Handshake]] — zeitbezogene Sicht auf Zustandsfolgen
