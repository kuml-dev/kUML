---
title: C4 Context – Notizen
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - c4
  - notiz
status: aktiv
---

# C4 Context – Notizen

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Notizen an Elementen und eine freistehende Notiz im Diagramm; `note(...)` war vorher ein No-Op. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.c4.dsl.c4Model

c4Model(name = "Shop") {
    val user = person(name = "User")
    val shop = softwareSystem(name = "Shop")
    val pay = softwareSystem(name = "Payment") { external = true }
    relationship(source = user, target = shop) { description = "uses" }
    relationship(source = shop, target = pay) { description = "pays via" }
    note("ASSUMPTION A1: payment is external", shop)
    note("Between two", user, pay, id = "twoNote")
    systemContextDiagram(name = "Context") {
        include(user, shop, pay)
        note("Free-standing note in the diagram", id = "freeNote")
    }
}
```
