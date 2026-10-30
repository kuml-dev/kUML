---
title: SysML 2 PAR – Notiz an einem verschachtelten Constraint
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - sysml2
  - par
  - notiz
status: aktiv
---

# SysML 2 PAR – Notiz an einem verschachtelten Constraint

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Eine Notiz an einem Constraint innerhalb eines Constraints. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Physics") {
    val outer = constraintDef("Outer", expression = "a == b")
    val inner = constraintDef("Inner", expression = "c == d", parent = outer)
    comment(text = "Holds only for rigid bodies.", firstAnchor = inner, id = "nn")
    parDiagram("Physics") {
        include(outer)
        include(inner)
    }
}
```
