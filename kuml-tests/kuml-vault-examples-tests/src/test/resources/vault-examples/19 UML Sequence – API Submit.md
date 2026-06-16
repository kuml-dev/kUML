---
title: UML Sequence – API Submit
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - sequence
status: aktiv
---

# UML Sequenzdiagramm — API Submit

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Sequenzdiagramm** zeigt zeitlich geordnete Nachrichten zwischen *Lifelines*. Hier: Ein Kunde stößt im Frontend eine Bestellung an, das Frontend schickt einen `POST /orders` ans Backend, das je nach Validität mit `201` oder `400` antwortet (`alt`-Fragment).

## Diagramm

```kuml
sequenceDiagram(name = "Place Order — API Submit") {
    val customer = lifeline(name = "Customer") { isActor = true }
    val frontend = lifeline(name = "Frontend")
    val backend  = lifeline(name = "Backend")
    val db       = lifeline(name = "OrderDB")

    message(from = customer, to = frontend, label = "submitOrder()")
    message(from = frontend, to = backend,  label = "POST /orders")

    alt {
        branch(guard = "[valid]") {
            message(from = backend, to = db, label = "INSERT order")
            reply(from = db, to = backend, label = "ok")
            reply(from = backend, to = frontend, label = "201 Created")
        }
        branch(guard = "[invalid]") {
            reply(from = backend, to = frontend, label = "400 Bad Request")
        }
    }

    reply(from = frontend, to = customer, label = "confirmation")
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `sequenceDiagram(name = …) { … }` | Top-Level: erzeugt ein UML-Sequenzdiagramm (eine `UmlInteraction`). |
| `lifeline(name = …) { isActor = … }` | Eine Lifeline; `isActor = true` ergibt das Strichmännchen-Symbol. |
| `message(from = …, to = …, label = …)` | Synchrone Nachricht. Sequenznummern werden automatisch in DSL-Reihenfolge vergeben. |
| `asyncMessage(...)`, `reply(...)`, `create(...)`, `delete(...)` | Verschiedene Message-Sorts. |
| `alt { branch(guard = …) { … }; branch(guard = …) { … } }` | Combined Fragment **alt** mit mehreren Operands. |
| `opt`, `loop`, `par`, `break_`, `fragment(...)` | Weitere Combined Fragments. |

## Mögliche Erweiterungen

- **Execution Specifications**: explizite Aktivitätsbalken über `activation { … }`
- **Self-Message**: `message(from = backend, to = backend, label = "validate()")`
- **Verschachtelte Fragments**: `loop { opt { … } }` für komplexere Kontrollflüsse

## Verwandte Beispiele

- [[06 SysML 2 SEQ – Login Flow]] — SysML-2-Pendant
- [[20 UML Communication – Place Order]] — semantisch äquivalente Sicht ohne Zeitachse
- [[17 UML Activity – Checkout Flow]] — Ablaufsicht statt Interaktionssicht
- [[26 C4 Dynamic – Checkout Flow]] — sequenzartige Sicht auf C4-Elemente
