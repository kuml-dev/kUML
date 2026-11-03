---
title: UML-Klasse – Formatierter Notiztext
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - uml
  - notiz
status: aktiv
---

# UML-Klasse – Formatierter Notiztext

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Der Notiztext versteht eine kleine Auszeichnungssprache: Fett, kursiv, Code, Listen und Links. Kombiniert mit einer Art entstehen lesbare, strukturierte Notizen. (PAL-Welle N2)

## Diagramm

```kuml
classDiagram(name = "Formatted notes") {
    val order = classOf(name = "Order") {
        attribute(name = "id", type = "String")
    }
    val line = classOf(name = "OrderLine") {
        attribute(name = "qty", type = "Int")
    }
    association(source = order, target = line)
    comment(text = "**Bold** and *italic* with `code()` and ***both***.", firstAnchor = order, kind = NoteKind.NOTE)
    comment(text = "Checks:\n- totals are **rounded**\n- ids are *unique*\n- see `validate()`", firstAnchor = order, kind = NoteKind.WARNING)
    comment(text = "Steps:\n1. validate\n1. reserve stock\n1. charge the card", firstAnchor = order, kind = NoteKind.TODO)
    comment(text = "Read the [kUML handbook](https://kuml.dev/handbook) before you start.", firstAnchor = line, kind = NoteKind.INFO)
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `**fett**`, `*kursiv*`, `***beides***` | Hervorhebungen im Fließtext |
| `` `code` `` | Code in Festbreitenschrift |
| `- ` und `1. ` | Aufzählung und nummerierte Liste; `\n` trennt die Zeilen |
| `[Text](https://...)` | Link; nur `http` und `https` werden zu Links |
