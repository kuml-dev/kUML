---
title: UML Communication – Place Order
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - communication
status: aktiv
---

# UML Kommunikationsdiagramm — Place Order

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Kommunikationsdiagramm** zeigt — semantisch äquivalent zum [[19 UML Sequence – API Submit|Sequenzdiagramm]] — wer mit wem Nachrichten austauscht, aber *ohne* explizite Zeitachse. Stattdessen werden die Nachrichten **nummeriert** und an den Linien zwischen den Rollen notiert.

## Diagramm

```kuml
communicationDiagram(name = "Place Order — Communication") {
    val ui       = role(classifierName = "Frontend", roleName = "ui")
    val api      = role(classifierName = "Backend",  roleName = "api")
    val db       = role(classifierName = "OrderDB",  roleName = "db")

    message(from = ui,  to = api, label = "submitOrder()")
    message(from = api, to = db,  label = "INSERT order")
    message(from = db,  to = api, label = "ok")
    message(from = api, to = ui,  label = "201 Created")
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `communicationDiagram(name = …) { … }` | Top-Level: erzeugt ein Kommunikationsdiagramm. |
| `role(classifierName = "Backend", roleName = "api")` | Eine Rolle — UmlInstanceSpecification mit Rollenname und Klassifikator. |
| `message(from = …, to = …, label = …)` | Nachricht; Sequenznummern werden in DSL-Reihenfolge vergeben. |
| (Mehrere Nachrichten zwischen denselben Rollen) | Werden zu einem Link mit kombinierten Labels gebündelt. |

## Sequenz- vs. Kommunikationsdiagramm

| Sequenz | Kommunikation |
|---|---|
| Zeit läuft vertikal nach unten | Keine Zeitachse — Reihenfolge durch Nummern |
| Lifelines im Vordergrund | Beziehungen zwischen Rollen im Vordergrund |
| Gut für detaillierte Protokolle | Gut für strukturelle Übersicht der Beteiligten |
| Combined Fragments möglich | Eher selten verzweigt |

## Mögliche Erweiterungen

- **Verschachtelte Nummern**: `1.1`, `1.2` für innere Nachrichten (manuell setzbar)
- **Asynchrone Pfeile**: Stereotype `«async»` am Label
- **Multiplicity in Rolle**: für eine Gruppe gleichartiger Rollen

## Verwandte Beispiele

- [[19 UML Sequence – API Submit]] — exakt derselbe Ablauf als Sequenz
- [[13 UML Composite Structure – Order Internals]] — strukturelle Grundlage der Rollen
