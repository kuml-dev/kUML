---
title: SysML 2 UC – Library System
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - sysml2
  - usecase
status: aktiv
---

# SysML 2 Use Case Diagram — Library System

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Klassisches **Bibliotheks-Use-Case-Diagramm** in SysML 2: zwei menschliche Akteure (Leser, Bibliothekar), ein externes System (Zahlungsdienst), vier Use Cases, mit `«include»`- und `«extend»`-Beziehungen zwischen ihnen.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("LibrarySystem") {

    // ── Actors ─────────────────────────────────────────────────────────────
    val reader = actorDef("Reader")
    val librarian = actorDef("Librarian")
    val paymentSystem = actorDef("PaymentSystem")

    // ── Use Cases ──────────────────────────────────────────────────────────
    val borrowBook = useCaseDef("BorrowBook")
    val returnBook = useCaseDef("ReturnBook")
    val payLateFee = useCaseDef("PayLateFee")
    val authenticate = useCaseDef("Authenticate")

    // ── Use Case Diagram ───────────────────────────────────────────────────
    ucDiagram("Library — top-level use cases") {
        // Nodes
        include(reader)
        include(librarian)
        include(paymentSystem)
        include(borrowBook)
        include(returnBook)
        include(payLateFee)
        include(authenticate)

        // Associations: actor "participates in" use case
        association(reader, borrowBook)
        association(reader, returnBook)
        association(reader, payLateFee)
        association(librarian, borrowBook)
        association(paymentSystem, payLateFee)

        // «include»: target is always executed as part of source
        include(borrowBook, authenticate)
        include(returnBook, authenticate)

        // «extend»: target's behaviour is optionally extended by source
        extend(payLateFee, returnBook)
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `actorDef("Reader")` | Akteur (menschlich oder externes System). Wird als Strichmännchen gerendert. |
| `useCaseDef("BorrowBook")` | Use Case. Wird als Ellipse gerendert. |
| `ucDiagram(name) { include(…) }` | Use Case Diagram. `include(node)` zieht den Knoten ins Diagramm. |
| `association(actor, useCase)` | Strichverbindung Akteur ↔ Use Case („Akteur nimmt teil"). |
| `include(uc, ucIncluded)` | **`«include»`** — `uc` führt `ucIncluded` immer aus. |
| `extend(ucExtender, ucBase)` | **`«extend»`** — `ucExtender` erweitert `ucBase` optional. |

> [!warning] Vorsicht: `include` hat zwei Bedeutungen
> Innerhalb von `ucDiagram { … }` macht `include(reader)` etwas anderes als `include(borrowBook, authenticate)`:
> - **`include(node)`** mit einem Argument → zieht den Knoten ins Diagramm
> - **`include(useCase, includedUseCase)`** mit zwei Argumenten → erzeugt eine `«include»`-Beziehung
>
> Beides ist legitim und nicht verwechselbar (Kotlin-Overloading auf Arität), aber beim Lesen lohnt der zweite Blick.

## `«include»` vs. `«extend»` — wann was?

| Beziehung | Wann | Beispiel |
|---|---|---|
| **`«include»`** | Wenn der Sub-Use-Case **immer** ausgeführt wird | `BorrowBook` → `Authenticate` (kein Buch ohne Login) |
| **`«extend»`** | Wenn der erweiternde Use Case **optional** unter bestimmten Bedingungen ausgeführt wird | `PayLateFee` erweitert `ReturnBook` (nur bei offener Gebühr) |

## Domain — wer macht was?

| Akteur | Beteiligte Use Cases |
|---|---|
| **Reader** | `BorrowBook`, `ReturnBook`, `PayLateFee` |
| **Librarian** | `BorrowBook` (assistierend) |
| **PaymentSystem** (extern) | `PayLateFee` (verarbeitet Zahlung) |

## Verwandte Beispiele

- [[02 C4 Container – Internet Banking]] — C4 modelliert Akteure auf Architektur-Ebene
- [[06 SysML 2 SEQ – Login Flow]] — wie `Authenticate` als Sequence aussehen könnte
