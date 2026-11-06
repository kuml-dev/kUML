---
title: SysML 2 STM – Notiz-Arten mit Symbolen
date: 2026-11-06
tags:
  - kUML
  - beispiel
  - sysml2
  - stm
  - notiz
status: aktiv
---

# SysML 2 STM – Notiz-Arten mit Symbolen

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Eine Ampel mit fünf Notiz-Arten: Fehler, Warnung, Info, Entscheidung und Todo. Jede Art hat ein eigenes Symbol und einen eigenen Farbton. Die Notizen stehen links und rechts neben ihrem Zustand (Nacharbeit 26), die Ankerlinien sind kurz und kreuzen nichts. Eine Notiz kann auch an einer Transition hängen (hier die Entscheidung an `stop`).

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Traffic light") {
    val start = stateDef(name = "Start", isInitial = true)
    val red = stateDef(name = "Red")
    val green = stateDef(name = "Green")
    val yellow = stateDef(name = "Yellow")
    transition(name = "t0", source = start, target = red)
    transition(name = "t1", source = red, target = green, trigger = "go")
    transition(name = "t2", source = green, target = yellow, trigger = "slow")
    val t3 = transition(name = "t3", source = yellow, target = red, trigger = "stop")
    stmDiagram(name = "Traffic light") {
        include(start)
        include(red)
        include(green)
        include(yellow)
        comment(text = "Error: a lamp that fails to switch must trigger the safe state.", firstAnchor = red, kind = NoteKind.ERROR)
        comment(text = "Warning: red and green must never be lit together.", firstAnchor = green, kind = NoteKind.WARNING)
        comment(text = "Info: yellow lasts three seconds.", firstAnchor = yellow, kind = NoteKind.INFO)
        comment(text = "Decision: a timer, not a sensor, ends the yellow phase.", firstAnchor = t3, kind = NoteKind.DECISION)
        comment(text = "Todo: add a flashing mode for the night.", firstAnchor = start, kind = NoteKind.TODO)
    }
}
```
