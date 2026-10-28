---
title: SysML 2 UC – Systemgrenzen und Generalisierung
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - uc
  - anwendungsfall
status: aktiv
---

# SysML 2 UC — Systemgrenzen und Akteur-Generalisierung

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Welle C1 für das Anwendungsfalldiagramm: `subject("Name") { … }` zeichnet eine Systemgrenze, in der die Anwendungsfälle liegen — Akteure bleiben konstruktionsbedingt außerhalb. `generalize(spezifisch, allgemein)` zeichnet den Generalisierungspfeil zwischen Akteuren, `useCaseDef(description = …)` zeigt eine kursive Kurzbeschreibung (max. 4 Zeilen) in der Ellipse.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Library") {
    val user = actorDef("User")
    val reader = actorDef("Reader")
    val librarian = actorDef("Librarian")
    val borrow = useCaseDef("Borrow Book", description = "A reader borrows a book for up to four weeks. The system checks the account and records the loan.")
    val auth = useCaseDef("Authenticate")
    val fee = useCaseDef("Pay Late Fee")
    val manage = useCaseDef("Manage Catalogue", description = "Add, change or remove titles in the catalogue of the library system.")
    val export = useCaseDef("Export Report")
    ucDiagram("Library system") {
        include(user)
        include(reader)
        include(librarian)
        include(borrow)
        include(auth)
        include(fee)
        include(manage)
        include(export)
        generalize(reader, user)
        generalize(librarian, user)
        association(reader, borrow)
        association(librarian, manage)
        association(librarian, export)
        include(borrow, auth)
        extend(fee, borrow)
        subject("Lending") {
            include(borrow)
            include(auth)
            include(fee)
        }
        subject("Administration") {
            include(manage)
            include(export)
        }
    }
}
```

## Was gezeigt wird

- Zwei Systemgrenzen nebeneinander; ein Anwendungsfall gehört höchstens einer Grenze an (Checker 205).
- Akteur-Generalisierung mit Dreiecksspitze; Zyklen und Nicht-Akteure meldet der Checker (206/207).
- Beschreibungen über 4 Zeilen werden abgeschnitten, der Volltext steht im `<title>`-Tooltip.

Verwandt: [[05 SysML 2 UC – Library System]]
