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
> Ein **UML-Zustandsdiagramm** (State Machine Diagram) zeigt, *wie sich ein Objekt über die Zeit verändert*. Hier: Der Lebenszyklus einer Bestellung von `Draft` über `Confirmed` und `Processing` (zusammengesetzter Zustand mit Substates `Picking`/`Packing`, einem `shallowHistory`-Pseudostate für Unterbrechungen und einem `choice`-Pseudostate für die Zahlungsprüfung) bis `Shipped` — inklusive Abbruch-Pfad. Demonstriert zusätzlich `exit`/`doActivity`-Verhaltensaktionen.

## Diagramm

```kuml
stateDiagram(name = "Order Lifecycle") {
    val start = initialState()
    val draft = state(name = "Draft") {
        entry = "validate()"
    }
    val confirmed = state(name = "Confirmed") {
        entry = "lockInventory()"
        exit = "releaseInventory()"
    }
    val paymentCheck = choice(name = "PaymentOK?")

    // Substates + History-Pseudostate werden im compositeState-Block deklariert
    lateinit var resume: UmlPseudostate
    lateinit var picking: UmlState
    lateinit var packing: UmlState
    val processing = compositeState(name = "Processing") {
        // History-Pseudostate: bei Rückkehr in "Processing" wird der zuletzt aktive Substate wiederhergestellt
        resume = shallowHistory(name = "H")
        picking = state(name = "Picking") {
            doActivity = "pickItems()"
        }
        packing = state(name = "Packing")
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
    transition(source = confirmed, target = paymentCheck) {
        trigger = "process()"
    }
    transition(source = paymentCheck, target = processing) { guard = "[payment.ok]" }
    transition(source = paymentCheck, target = cancelled)  { guard = "[else]" }

    // Innere Übergänge werden — wie im compositeState-KDoc beschrieben — auf State-Machine-Ebene deklariert
    transition(source = resume, target = picking)
    transition(source = picking, target = packing) { trigger = "packed" }

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
| `state(name = …) { entry = …; exit = …; doActivity = … }` | Einfacher Zustand mit optionalen Verhaltensaktionen. `entry`/`exit` laufen beim Eintritt/Verlassen, `doActivity` läuft dauerhaft solange der Zustand aktiv ist. |
| `compositeState(name = …) { state(…); state(…) }` | Geschachtelter Zustand mit Substates. |
| `choice(name = …)` | Pseudo-State für bedingte Verzweigung — mehrere ausgehende Transitions mit `guard`, genau eine feuert. |
| `shallowHistory(name = …)` | Pseudo-State **H** — merkt sich beim Verlassen des Composite-States den zuletzt aktiven direkten Substate und stellt ihn beim Wiedereintritt wieder her. `deepHistory` für die rekursive Variante über alle Verschachtelungsebenen. |
| `fork()` / `join()` / `junction()` | Weitere Pseudo-States: Aufspaltung in parallele Regionen, Zusammenführung paralleler Regionen, bzw. reiner Verzweigungspunkt ohne Bedingung. |
| `transition(source, target) { trigger = …; guard = …; effect = … }` | Zustandsübergang mit Auslöser, Bedingung und Effekt. Übergänge zwischen Substates eines `compositeState` werden — laut KDoc — auf State-Machine-Ebene deklariert, nicht im `compositeState`-Block selbst. |

## Mögliche Erweiterungen

- **Fork/Join**: `val split = fork(); val merge = join()` für parallele Regionen (z. B. Rechnung + Versand-Label gleichzeitig erzeugen)
- **Deep History**: `deepHistory(name = "H*")` statt `shallowHistory`, wenn die Wiederherstellung rekursiv durch alle Verschachtelungsebenen gehen soll

## Verwandte Beispiele

- [[04 SysML 2 STM – Traffic Light]] — SysML-2-Pendant
- [[17 UML Activity – Checkout Flow]] — Ablauf statt Zustände
- [[21 UML Timing – TCP Handshake]] — zeitbezogene Sicht auf Zustandsfolgen
