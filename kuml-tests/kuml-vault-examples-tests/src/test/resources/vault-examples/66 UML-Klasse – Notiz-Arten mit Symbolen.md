---
title: UML-Klasse – Notiz-Arten mit Symbolen
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - uml
  - notiz
status: aktiv
---

# UML-Klasse – Notiz-Arten mit Symbolen

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Notizen tragen eine Art (`NoteKind`): Hinweis, Warnung, Entscheidung, Aufgabe, Risiko, veraltet. Jede Art außer `NOTE` bekommt ein eigenes Symbol, einen farbigen Streifen und eine passende Farbe. (PAL-Welle N1)

## Diagramm

```kuml
classDiagram(name = "Note kinds") {
    val order = classOf(name = "Order") {
        attribute(name = "id", type = "String")
        attribute(name = "total", type = "Decimal")
    }
    val line = classOf(name = "OrderLine") {
        attribute(name = "qty", type = "Int")
    }
    association(source = order, target = line)
    comment(text = "A plain note without a symbol.", firstAnchor = order, kind = NoteKind.NOTE)
    comment(text = "Warning: totals are not rounded here.", firstAnchor = order, kind = NoteKind.WARNING)
    comment(text = "Decision: orders are immutable once paid.", firstAnchor = order, kind = NoteKind.DECISION)
    comment(text = "Todo: add the discount rules.", firstAnchor = line, kind = NoteKind.TODO)
    comment(text = "Risk: a large order may exceed the gateway limit.", firstAnchor = line, kind = NoteKind.RISK)
    comment(text = "Deprecated: use Invoice instead of Bill.", firstAnchor = line, kind = NoteKind.DEPRECATED)
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `comment(..., kind = NoteKind.X)` | Art der Notiz; 15 Arten stehen zur Wahl, hier eine Auswahl von sechs |
| `NoteKind.NOTE` | Einfache Notiz ohne Symbol und ohne Streifen |
| Symbol und Streifen | Warnung, Entscheidung, Aufgabe, Risiko und veraltet sind auf einen Blick zu unterscheiden |
