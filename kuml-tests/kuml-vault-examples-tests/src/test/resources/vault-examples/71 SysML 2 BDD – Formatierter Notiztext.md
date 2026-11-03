---
title: SysML 2 BDD – Formatierter Notiztext
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - sysml2
  - notiz
status: aktiv
---

# SysML 2 BDD – Formatierter Notiztext

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Die Auszeichnung im Notiztext gilt in allen Notationen gleich. Hier am SysML-2-BDD mit Liste, Hervorhebung und Link. (PAL-Welle N2)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Formatted notes") {
    val vehicle = partDef(name = "Vehicle")
    val engine = partDef(name = "Engine")
    bdd(name = "Formatted notes") {
        include(vehicle)
        include(engine)
        comment(text = "**Requirement:** the *engine* needs `12V`.\n- starts within 2 s\n- runs at idle", firstAnchor = vehicle, kind = NoteKind.INFO)
        comment(text = "Risk: ***fatal overheating*** is possible, see [the spec](https://example.org/spec).", firstAnchor = engine, kind = NoteKind.RISK)
    }
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| Fett, kursiv, Code | Wie im UML-Beispiel, ein gemeinsamer Parser für alle Notationen |
| Aufzählung | Eingerückte Listenpunkte in der Notiz |
| Link | Unterstrichener Text, Ziel nur bei `http`/`https` |
