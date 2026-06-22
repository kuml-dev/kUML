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

sysml2Model(name = "LibrarySystem") {

    // ── Actors ─────────────────────────────────────────────────────────────
    val reader = actorDef(name = "Reader")
    val librarian = actorDef(name = "Librarian")
    val paymentSystem = actorDef(name = "PaymentSystem")

    // ── Use Cases ──────────────────────────────────────────────────────────
    val borrowBook = useCaseDef(name = "BorrowBook")
    val returnBook = useCaseDef(name = "ReturnBook")
    val payLateFee = useCaseDef(name = "PayLateFee")
    val authenticate = useCaseDef(name = "Authenticate")

    // ── Use Case Diagram ───────────────────────────────────────────────────
    ucDiagram(name = "Library — top-level use cases") {
        // Nodes
        include(definition = reader)
        include(definition = librarian)
        include(definition = paymentSystem)
        include(definition = borrowBook)
        include(definition = returnBook)
        include(definition = payLateFee)
        include(definition = authenticate)

        // Associations: actor "participates in" use case
        association(actor = reader, useCase = borrowBook)
        association(actor = reader, useCase = returnBook)
        association(actor = reader, useCase = payLateFee)
        association(actor = librarian, useCase = borrowBook)
        association(actor = paymentSystem, useCase = payLateFee)

        // «include»: target is always executed as part of source
        include(source = borrowBook, target = authenticate)
        include(source = returnBook, target = authenticate)

        // «extend»: target's behaviour is optionally extended by source
        extend(source = payLateFee, target = returnBook)
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `actorDef(name = "Reader")` | Akteur (menschlich oder externes System). Wird als Strichmännchen gerendert. |
| `useCaseDef(name = "BorrowBook")` | Use Case. Wird als Ellipse gerendert. |
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
