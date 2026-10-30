---
title: SysML 2 SEQ – Notizen an Lebenslinien
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - sysml2
  - seq
  - notiz
status: aktiv
---

# SysML 2 SEQ – Notizen an Lebenslinien

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Die Anker-Linie endet auf dem Lebenslinien-Stamm, nicht am Kopf. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Ping") {
    val a = lifelineDef("A")
    val b = lifelineDef("B")
    val c = lifelineDef("C")
    message(label = "ping", source = a, target = b, seqNo = 0)
    message(label = "pong", source = b, target = c, seqNo = 1)
    comment(text = "B is the inner lifeline.", firstAnchor = b, id = "nb")
    seqDiagram("Ping") {
        include(a)
        include(b)
        include(c)
    }
}
```
