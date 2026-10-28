---
title: SysML 2 SEQ – Verschachtelte Fragmente, ref, Zeit
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - seq
status: aktiv
---

# SysML 2 SEQ — Verschachtelte Fragmente, Interaktions-Referenz und Zeitconstraint

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Vier Lebenslinien in fester Reihenfolge, ein `alt`-Fragment mit verschachteltem `loop` (Tiefe 2), eine Interaktions-Referenz (`ref`), ein Zeitconstraint `{t <= 2 s}` sowie je eine gefundene (`found`) und eine verlorene (`lost`) Nachricht.

## Diagramm

```kuml
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "OrderPayment") {
    val customer = lifelineDef(name = "Customer")
    val shop = lifelineDef(name = "Shop")
    val payment = lifelineDef(name = "Payment")
    val bank = lifelineDef(name = "Bank")

    foundMessage(label = "newOrder", target = shop, seqNo = 0)
    message(label = "charge(total)", source = shop, target = payment, seqNo = 1, kind = MessageKind.Sync)
    message(label = "authorize", source = payment, target = bank, seqNo = 2, kind = MessageKind.Sync)

    combinedFragment(name = "result", operator = CombinedFragmentOperator.Alt) {
        operand(guard = "approved", start = 3, end = 6) {
            combinedFragment(name = "receipts", operator = CombinedFragmentOperator.Loop) {
                operand(guard = "for each item", start = 5, end = 6)
            }
        }
        operand(guard = "else", start = 7, end = 8)
    }
    message(label = "approved", source = bank, target = payment, seqNo = 3, kind = MessageKind.Reply)
    message(label = "paid", source = payment, target = shop, seqNo = 4, kind = MessageKind.Reply)
    message(label = "receipt()", source = shop, target = customer, seqNo = 5, kind = MessageKind.Sync)
    message(label = "ack", source = customer, target = shop, seqNo = 6, kind = MessageKind.Reply)
    message(label = "declined", source = payment, target = shop, seqNo = 7, kind = MessageKind.Reply)
    lostMessage(label = "notifyLater", source = shop, seqNo = 8)

    interactionUse(name = "audit", refersTo = "AuditTrail", lifelines = listOf(shop, payment), seqNo = 10)
    durationConstraint(lifeline = payment, from = 1, to = 4, expression = "t <= 2 s")

    seqDiagram(name = "Order payment") {
        include(customer)
        include(shop)
        include(payment)
        include(bank)
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `foundMessage(label, target, seqNo)` | Nachricht ohne bekannten Sender (gefüllter Punkt am Anfang). |
| `lostMessage(label, source, seqNo)` | Nachricht ohne Empfänger. |
| `combinedFragment(name, operator) { operand(...) { combinedFragment(...) } }` | Fragment; ein `operand`-Block kann weitere Fragmente enthalten (Tiefe bis 8). |
| `interactionUse(name, refersTo, lifelines, seqNo)` | `ref`-Rahmen über den beteiligten Lebenslinien. |
| `durationConstraint(lifeline, from, to, expression)` | Zeitconstraint zwischen zwei Sequenznummern. |

## Verwandte Beispiele

- [[06 SysML 2 SEQ – Login Flow]] — einfacher Ablauf

