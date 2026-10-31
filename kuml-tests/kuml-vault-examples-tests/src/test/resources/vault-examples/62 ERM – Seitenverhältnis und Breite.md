---
title: ERM – Seitenverhältnis und Breite
date: 2026-10-31
tags:
  - kUML
  - beispiel
  - erm
  - layout
status: aktiv
---

# ERM – Seitenverhältnis und Breite

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Acht Tabellen um eine zentrale Tabelle. `aspectRatio = 1.5` und `maxWidth = 1400` halten das Diagramm kompakt statt es in eine breite Zeile zu ziehen. (PAL-Welle 2B-b)

## Diagramm

```kuml
ermModel("Shop Schema") {
    val hub = entity("Customer") {
        id()
        attribute(name = "email", type = ErmDataType.Varchar(255), unique = true, nullable = false)
    }
    val e0 = entity("Address") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }
    val e1 = entity("Invoice") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }
    val e2 = entity("Payment") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }
    val e3 = entity("Shipment") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }
    val e4 = entity("Return") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }
    val e5 = entity("Review") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }
    val e6 = entity("Wishlist") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }
    val e7 = entity("Coupon") {
        id()
        attribute(name = "name", type = ErmDataType.Varchar(120))
        foreignKey(name = "hub_id", references = hub, nullable = false)
    }

    diagram(name = "Compact schema") {
        layout {
            aspectRatio = 1.5
            maxWidth = 1400
        }
    }
}
```

## Ohne Hinweis

Ohne `layout { }`-Block stehen die acht Tabellen in einer Schicht nebeneinander.
