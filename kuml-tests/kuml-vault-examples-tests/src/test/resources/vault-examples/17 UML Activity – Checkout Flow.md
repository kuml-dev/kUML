---
title: UML Activity – Checkout Flow
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - activity
status: aktiv
---

# UML Aktivitätsdiagramm — Checkout Flow

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Aktivitätsdiagramm** modelliert prozedurale Abläufe: Aktionen, Entscheidungen, Verzweigungen. Hier: der Checkout-Fluss eines Online-Shops von der Kartenvalidierung über die Bezahlung bis zum Abschluss — mit einem Decision-Node und einem Cancel-Pfad.

## Diagramm

```kuml
activityDiagram(name = "Checkout") {
    val start = initialNode()
    val verify = action(name = "Verify cart")
    val ok = decision(name = "valid?")
    val charge = action(name = "Charge card")
    val confirm = action(name = "Send confirmation")
    val cancel = action(name = "Notify error")
    val done = finalNode()

    edge(from = start, to = verify)
    edge(from = verify, to = ok)
    edge(from = ok, to = charge, guard = "ok")
    edge(from = ok, to = cancel, guard = "invalid")
    edge(from = charge, to = confirm)
    edge(from = confirm, to = done)
    edge(from = cancel, to = done)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `activityDiagram(name = …) { … }` | Top-Level: erzeugt ein Aktivitätsdiagramm. |
| `initialNode()` / `finalNode()` | Start- und Endknoten. |
| `action(name = …)` | Atomare Aktion (Aufgabe, Schritt). |
| `decision(name = …)` | Entscheidungspunkt — eingehender Pfad, mehrere ausgehende mit `guard`. |
| `edge(from = …, to = …, guard = …)` | Kontrollfluss-Kante mit optionalem Guard-Ausdruck. |
| `forkNode()` / `joinNode()` | Parallelisierung und Synchronisation (siehe Erweiterungen). |
| `mergeNode()` | Zusammenführung mehrerer Alternativen ohne Synchronisation. |

## Mögliche Erweiterungen

- **Parallele Pfade**: `forkNode()` + `joinNode()` für Versand und Rechnungsstellung parallel
- **Object Flow**: siehe [[41 UML Activity – Order Fulfillment (Objektfluss)]] — eigenes Beispiel, da Objektfluss-Knoten (`objectNode`) und -Kanten (`edge(objectFlow = true)`) den Fokus eigenständig verdienen
- **Partitions (Swimlanes)**: `partition("Customer") { … }` für Verantwortungsbereiche

## Verwandte Beispiele

- [[41 UML Activity – Order Fulfillment (Objektfluss)]] — gleicher Diagrammtyp, Fokus auf Objektfluss statt Kontrollfluss
- [[07 SysML 2 ACT – Order Processing]] — SysML-2-Pendant mit Swimlanes
- [[18 UML State Machine – Order Lifecycle]] — Zustandssicht statt Ablaufsicht
- [[22 UML Interaction Overview – Order Process]] — Aktivitäten verlinken Sub-Interaktionen
