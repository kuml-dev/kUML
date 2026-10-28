---
title: SysML 2 STM – Prosa-Guards
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - stm
  - zustandsmaschine
  - guards
status: aktiv
---

# SysML 2 STM — Prosa-Guards (Freitext-Bedingungen)

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Nicht jede Bedingung ist ein auswertbarer Ausdruck. In einem Genehmigungsprozess sagt `guard = prose(text = "…")`, dass hier ein **Mensch** entscheidet. Das Diagramm zeigt den Text kursiv in eckigen Klammern; `kuml validate --strict` versucht nicht, ihn zu parsen; der Simulator **blockiert** ihn standardmäßig (`--prose-guards block`), erlaubt ihn mit `allow` oder fragt im Desktop-Editor per Dialog.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Genehmigung") {

    val initial = stateDef(name = "Initial", isInitial = true)
    val submitted = stateDef(name = "Submitted")
    val review = stateDef(name = "InReview")
    val approved = stateDef(name = "Approved", isFinal = true)
    val rejected = stateDef(name = "Rejected", isFinal = true)

    transition(name = "init", source = initial, target = submitted)
    transition(name = "startReview", source = submitted, target = review, trigger = "assign")

    // Freitext: der Simulator weiß nicht, ob der Antrag vollständig ist
    transition(
        name = "approve",
        source = review,
        target = approved,
        trigger = "decide",
        guard = prose(text = "Antrag vollständig und Gebühr bezahlt"),
        effect = "notifyApplicant()",
    )
    // Auswertbar: ein normaler Ausdruck, daneben im selben Diagramm
    transition(
        name = "reject",
        source = review,
        target = rejected,
        trigger = "decide",
        guard = "vars.documentsMissing > 2",
    )

    stmDiagram(name = "Genehmigung") {
        include(state = initial)
        include(state = submitted)
        include(state = review)
        include(state = approved)
        include(state = rejected)
    }
}
```

## Was gezeigt wird

- `guard = prose(text = …)` markiert den Guard als Freitext (`GuardKind.Prose`); Ausdrücke bleiben `guard = "…"`.
- Im Diagramm erscheint der Prosa-Guard **kursiv**, der Ausdruck normal.
- Erweiterte Ausdrucksgrammatik für auswertbare Guards: `and` / `or` / `not` und Bezeichner in Backticks (``` `Tür offen` ```).
- In der Spur steht bei blockierten Guards `GuardNotEvaluable`.

Verwandt: [[42 SysML 2 STM – Timer]]
