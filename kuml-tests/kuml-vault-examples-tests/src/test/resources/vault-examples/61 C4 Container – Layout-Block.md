---
title: C4 Container – Layout-Block
date: 2026-10-31
tags:
  - kUML
  - beispiel
  - c4
  - layout
status: aktiv
---

# C4 Container – Layout-Block

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> `layout { }` gilt jetzt auch für C4: Fluss von links nach rechts, höchstens drei Container je Schicht. (PAL-Welle 2B-b)

## Diagramm

```kuml
c4Model(name = "Shop Platform") {
    val customer = person(name = "Customer")
    lateinit var web: dev.kuml.c4.model.C4Container
    lateinit var api: dev.kuml.c4.model.C4Container
    lateinit var catalog: dev.kuml.c4.model.C4Container
    lateinit var orders: dev.kuml.c4.model.C4Container
    lateinit var payments: dev.kuml.c4.model.C4Container
    lateinit var search: dev.kuml.c4.model.C4Container

    val shop = softwareSystem(name = "Shop") {
        web = container(name = "Web App") { technology = "Kotlin/KVision" }
        api = container(name = "API") { technology = "Kotlin/Ktor" }
        catalog = container(name = "Catalog") { technology = "Kotlin/Exposed" }
        orders = container(name = "Orders") { technology = "Kotlin/Exposed" }
        payments = container(name = "Payments") { technology = "Kotlin/Ktor" }
        search = container(name = "Search") { technology = "OpenSearch" }
    }

    relationship(source = customer, target = web) { description = "browses" }
    relationship(source = web, target = api) { description = "calls" }
    relationship(source = api, target = catalog) { description = "reads" }
    relationship(source = api, target = orders) { description = "places" }
    relationship(source = api, target = payments) { description = "pays" }
    relationship(source = catalog, target = search) { description = "indexes" }

    containerDiagram(name = "Shop — Containers") {
        system = shop
        layout {
            direction = Right
            wrapColumns = 3
        }
    }
}
```

## Hinweis

`direction`, `wrapColumns`, `aspectRatio`, `maxWidth`, `sameLayer`, `first` und `last` wirken auf alle C4-Diagramme. Ohne Block gilt die Voreinstellung der Engine.
