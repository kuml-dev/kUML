---
title: UML Interaction Overview – Order Process
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - interaction-overview
status: aktiv
---

# UML Interaction-Overview-Diagramm — Order Process

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Interaction-Overview-Diagramm** verbindet die Kontrollfluss-Konstrukte eines Aktivitätsdiagramms mit Verweisen auf vollständige Sub-Interaktionen (z. B. Sequenzdiagramme). Es ist eine *Landkarte* von Use-Case-Abläufen: „Login → Search → Checkout → ShipNotify". Jeder Knoten verweist auf ein eigenes detailliertes Interaktionsdiagramm.

## Diagramm

```kuml
interactionOverviewDiagram(name = "Order Process Overview") {
    val start    = initial()
    val login    = interactionRef(name = "Login")
    val search   = interactionRef(name = "Search Catalog")
    val checkout = interactionRef(name = "Checkout")
    val notify   = interactionRef(name = "Ship Notification")
    val end      = final()

    edge(from = start,    to = login)
    edge(from = login,    to = search)
    edge(from = search,   to = checkout)
    edge(from = checkout, to = notify)
    edge(from = notify,   to = end)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `interactionOverviewDiagram(name = …) { … }` | Top-Level: erzeugt ein Interaction-Overview-Diagramm. |
| `initial()` / `final()` | Start- und Endknoten — wie im Aktivitätsdiagramm. |
| `interactionRef(name = "Login")` | Verweis auf ein bestehendes Interaktionsdiagramm (z. B. Sequenz). |
| `edge(from = …, to = …, guard = …)` | Kontrollfluss-Kante zwischen Frames. |

## Mögliche Erweiterungen

- **Decision-Knoten**: zwischen `search` und `checkout`, abhängig davon ob Warenkorb leer ist
- **Inline-Interaktionen**: statt nur `interactionRef` auch ein vollständiger `sequenceDiagram`-Block inline
- **Verschachtelte Overviews**: ein Frame referenziert ein weiteres Overview

## Verwandte Beispiele

- [[17 UML Activity – Checkout Flow]] — kontrollfluss-orientiert, ohne Interaktionsverweise
- [[19 UML Sequence – API Submit]] — eines der referenzierten Sub-Diagramme
- [[16 UML Use Case – Online Shop]] — fachliche Use-Cases hinter den Frames
