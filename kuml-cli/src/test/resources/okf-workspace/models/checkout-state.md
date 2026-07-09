---
type: UmlStateMachine
title: Checkout State Machine
tags: [shop, uml, state-machine]
---

# Checkout State Machine

The lifecycle of an [Order](../concepts/Bestellung.md) as it moves through checkout.

```kuml
stateDiagram(name = "Checkout Lifecycle") {
    val init = initialState()
    val draft = state(name = "Draft") {
        entry = "validate()"
    }
    val confirmed = state(name = "Confirmed") {
        entry = "reserveStock()"
    }
    val shipped = finalState(name = "Shipped")
    val cancelled = finalState(name = "Cancelled")

    transition(source = init, target = draft)
    transition(source = draft, target = confirmed) { trigger = "confirm()"; guard = "[isValid]" }
    transition(source = confirmed, target = shipped) { trigger = "ship()" }
    transition(source = draft, target = cancelled) { trigger = "cancel()" }
    transition(source = confirmed, target = cancelled) { trigger = "cancel()" }
}
```

Back to [Overview](../articles/01-ueberblick.md).
