---
title: SysML 2 BDD – Notiz-Arten
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - sysml2
  - notiz
status: aktiv
---

# SysML 2 BDD – Notiz-Arten

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Dieselben Notiz-Arten am SysML-2-Blockdefinitionsdiagramm: `comment` und `note` nehmen ein `kind` entgegen. (PAL-Welle N1)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Note kinds") {
    val vehicle = partDef(name = "Vehicle")
    val engine = partDef(name = "Engine")
    bdd(name = "Note kinds") {
        include(vehicle)
        include(engine)
        comment(text = "A plain note without a symbol.", firstAnchor = vehicle, kind = NoteKind.NOTE)
        comment(text = "Important: the engine must be certified.", firstAnchor = vehicle, kind = NoteKind.IMPORTANT)
        comment(text = "Assumption: one engine per vehicle.", firstAnchor = vehicle, kind = NoteKind.ASSUMPTION)
        comment(text = "Question: is a hybrid drive a second engine?", firstAnchor = engine, kind = NoteKind.QUESTION)
        comment(text = "Reference: see the powertrain specification.", firstAnchor = engine, kind = NoteKind.REFERENCE)
    }
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `comment(..., kind = NoteKind.X)` | Art der Notiz im BDD |
| `IMPORTANT`, `ASSUMPTION`, `QUESTION`, `REFERENCE` | Vier Arten mit eigenem Symbol und Farbton |
| Notizen links und rechts | Die Anker-Notizen ordnen sich neben ihren Blöcken an |
