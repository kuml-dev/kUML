---
title: C4 Dynamic – Checkout Flow
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - c4
  - dynamic
status: aktiv
---

# C4 Dynamic — Checkout Flow

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **C4 Dynamic Diagram** zeigt zeitlich geordnete Interaktionen zwischen C4-Elementen — eine sequenzartige Sicht auf Systemfluss. Hier: der Checkout-Ablauf vom Kunden über Frontend und Backend bis in die Datenbank, mit nummerierten Schritten und Antworten.

## Diagramm

```kuml
c4Model(name = "Checkout — Dynamic") {
    val customer = person(name = "Customer")
    val web      = softwareSystem(name = "WebApp")
    val api      = softwareSystem(name = "API Server")
    val db       = softwareSystem(name = "OrderDB")

    dynamicDiagram(name = "Checkout Flow", description = "Bestellung abschicken") {
        interaction(description = "Submit order",   from = customer, to = web,
                    technology = "HTTPS")
        interaction(description = "POST /orders",   from = web,      to = api,
                    technology = "HTTPS/JSON")
        interaction(description = "INSERT order",   from = api,      to = db,
                    technology = "JDBC")
        response   (description = "ok",             from = db,       to = api)
        response   (description = "201 Created",    from = api,      to = web)
        response   (description = "Confirmation",   from = web,      to = customer)
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `dynamicDiagram(name = …, description = …) { … }` | Erzeugt ein Dynamic-Diagramm. |
| `interaction(description = …, from = …, to = …, technology = …)` | Nachricht von Element A zu B. Sequenznummern werden automatisch in DSL-Reihenfolge vergeben. |
| `response(description = …, from = …, to = …)` | Antwort-Nachricht (gestrichelter Pfeil im Renderer). |

## C4 Dynamic vs. UML Sequence

| C4 Dynamic | UML Sequence |
|---|---|
| Architektur-Ebene (Systeme, Container, Components) | Klassen-/Objektebene |
| Implizite Sequenznummern, keine Lifelines | Explizite Lifelines, Activation Bars |
| Keine Combined Fragments (`alt`, `loop`) | Vollständige Fragment-Algebra |
| Schnell für Architektur-Reviews | Detailgenau für Implementierungs-Modellierung |

## Mögliche Erweiterungen

- **Mehrere Dynamic-Diagramme**: pro Use-Case (Login, Checkout, Cancel)
- **Aufruf eines Components statt Systems**: `from = securityController, to = authApi`
- **Branching**: für Alternativen ein zweites Dynamic-Diagramm pro Pfad

## Verwandte Beispiele

- [[19 UML Sequence – API Submit]] — UML-Pendant mit Combined Fragments
- [[06 SysML 2 SEQ – Login Flow]] — SysML-2-Pendant
- [[02 C4 Container – Internet Banking]] — Strukturelle Sicht der Elemente
