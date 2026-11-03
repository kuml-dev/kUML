---
title: C4 Context – Notiz-Arten und Formatierung
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - c4
  - notiz
status: aktiv
---

# C4 Context – Notiz-Arten und Formatierung

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Notizen am C4-Systemkontextdiagramm: eine Notiz mit Entscheidungs-Symbol und Auszeichnung, eine an einem externen System und eine freistehende Frage mit Liste. Das Diagramm ist ein Systemkontext. (PAL-Welle N1/N2)

## Diagramm

```kuml
import dev.kuml.c4.dsl.c4Model

c4Model(name = "Shop") {
    val user = person(name = "User")
    val shop = softwareSystem(name = "Shop")
    val pay = softwareSystem(name = "Payment") { external = true }
    relationship(source = user, target = shop) { description = "uses" }
    relationship(source = shop, target = pay) { description = "pays via" }
    note(text = "**Decision:** orders are *immutable* once paid, see [**the ADR**](https://kuml.dev/adr).", shop, kind = NoteKind.DECISION)
    note(text = "Payment runs through an external provider.", pay, kind = NoteKind.INFO)
    systemContextDiagram(name = "Context") {
        include(user, shop, pay)
        note(text = "Question: should refunds be `separate`?\n- yes\n- no", id = "freeNote", kind = NoteKind.QUESTION)
    }
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `note(text, element, kind = NoteKind.X)` | Notiz am Element, mit Art |
| Freistehende Notiz im Diagramm | `note(...)` im Diagramm-Block ohne Anker |
| Auszeichnung | Fett, kursiv, Code, Liste und Link in derselben Notiz |
