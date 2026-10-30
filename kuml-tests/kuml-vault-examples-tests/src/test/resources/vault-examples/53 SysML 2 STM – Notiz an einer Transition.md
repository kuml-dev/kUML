---
title: SysML 2 STM – Notiz an einer Transition
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - sysml2
  - stm
  - notiz
status: aktiv
---

# SysML 2 STM – Notiz an einer Transition

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Eine Notiz kann an eine Transition geheftet werden. Die Anker-Linie trifft die Transition, nicht einen Zustand. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Door") {
    val closed = stateDef("Closed", isInitial = true)
    val open = stateDef("Open")
    transition(name = "t1", source = closed, target = open, trigger = "openCmd")
    val t2 = transition(name = "t2", source = open, target = closed, trigger = "closeCmd")
    stmDiagram("Door") {
        include(closed)
        include(open)
        comment(text = "Closing is only allowed when nothing blocks the door.", firstAnchor = t2, id = "nt")
    }
}
```
