---
title: SysML 2 REQ – Mehrere Notizen
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - sysml2
  - req
  - notiz
status: aktiv
---

# SysML 2 REQ – Mehrere Notizen

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Mehrere Notizen am selben Diagramm: Anker-Notizen neben ihren Anforderungen, freistehende im Raster. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Reqs") {
    val boot = requirementDef(name = "Boot", text = "The platform shall boot within 5 seconds.")
    val update = requirementDef(name = "Update", text = "Updates shall install without user interaction.")
    comment(text = "Measured from power-on.", firstAnchor = boot, id = "n1")
    comment(text = "See the update policy.", firstAnchor = update, id = "n2")
    reqDiagram("Reqs") {
        include(boot)
        include(update)
        comment(text = "Free note one", id = "f1")
        comment(text = "Free note two", id = "f2")
    }
}
```
