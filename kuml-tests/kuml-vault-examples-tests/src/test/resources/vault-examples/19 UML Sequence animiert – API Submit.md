---
title: UML Sequence animiert – API Submit
date: 2026-06-30
tags:
  - kUML
  - beispiel
  - uml
  - sequence
  - smil
  - animiert
status: aktiv
---

# UML Sequenzdiagramm animiert — API Submit

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Dasselbe **UML-Sequenzdiagramm** wie [[19 UML Sequence – API Submit]], aber mit **SMIL-Message-Dot-Animation**: ein blauer Punkt wandert bei jeder Nachricht horizontal von der Sender-Lifeline zur Empfänger-Lifeline. Der Trace zeigt den Happy-Path (gültige Bestellung).

## Animiertes Diagramm (kuml-animated)

> [!tip] Obsidian Plugin v0.3.0+
> Der `kuml-animated`-Code-Block rendert das Sequenzdiagramm mit message-dot-getriebenem SMIL. Jede Nachricht erhält einen animierten Dot, der horizontal über die Pfeilachse wandert. Die Animation läuft **endlos in Schleife**.

```kuml-animated
// trace: inline
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

## Statisches Diagramm (Referenz)

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

## Trace — Happy Path (valid)

```json
{
  "schema": "kuml.trace.v1",
  "entries": [
    {"type": "MessageSent", "seqNo": 0, "timestamp": "2026-06-30T10:00:00Z", "messageId": "Place Order — API Submit::msg::1", "fromLifelineId": "Place Order — API Submit::ll::Customer", "toLifelineId": "Place Order — API Submit::ll::Frontend"},
    {"type": "MessageSent", "seqNo": 1, "timestamp": "2026-06-30T10:00:01Z", "messageId": "Place Order — API Submit::msg::2", "fromLifelineId": "Place Order — API Submit::ll::Frontend", "toLifelineId": "Place Order — API Submit::ll::Backend"},
    {"type": "MessageSent", "seqNo": 2, "timestamp": "2026-06-30T10:00:02Z", "messageId": "Place Order — API Submit::msg::3", "fromLifelineId": "Place Order — API Submit::ll::Backend", "toLifelineId": "Place Order — API Submit::ll::OrderDB"},
    {"type": "MessageSent", "seqNo": 3, "timestamp": "2026-06-30T10:00:03Z", "messageId": "Place Order — API Submit::msg::4", "fromLifelineId": "Place Order — API Submit::ll::OrderDB", "toLifelineId": "Place Order — API Submit::ll::Backend"},
    {"type": "MessageSent", "seqNo": 4, "timestamp": "2026-06-30T10:00:04Z", "messageId": "Place Order — API Submit::msg::5", "fromLifelineId": "Place Order — API Submit::ll::Backend", "toLifelineId": "Place Order — API Submit::ll::Frontend"},
    {"type": "MessageSent", "seqNo": 5, "timestamp": "2026-06-30T10:00:05Z", "messageId": "Place Order — API Submit::msg::7", "fromLifelineId": "Place Order — API Submit::ll::Frontend", "toLifelineId": "Place Order — API Submit::ll::Customer"}
  ]
}
```

## Trace-Erklärung

| Schritt | Nachricht | Richtung | ID |
|---------|-----------|----------|----|
| 0 | `submitOrder()` | Customer → Frontend | `msg::1` |
| 1 | `POST /orders` | Frontend → Backend | `msg::2` |
| 2 | `INSERT order` | Backend → OrderDB | `msg::3` |
| 3 | `ok` | OrderDB → Backend | `msg::4` |
| 4 | `201 Created` | Backend → Frontend | `msg::5` |
| 5 | `confirmation` | Frontend → Customer | `msg::7` |

> [!note] `msg::7` statt `msg::6`
> Die DSL vergibt Sequenznummern in Deklarationsreihenfolge über alle `branch`-Blöcke hinweg. `msg::6` ist `400 Bad Request` (im `[invalid]`-Branch) — der Happy-Path überspringt diese Nachricht.

## Verwandte Beispiele

- [[19 UML Sequence – API Submit]] — statische Version
- [[07 BPMN animiert – PdV Mitgliedsantrag]] — BPMN-Token-Animation
- [[08 STM animiert – Traffic Light]] — STM-State-Animation
