---
title: SysML 2 REQ – Breites Diagramm mit wrapColumns
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - req
  - layout
status: aktiv
---

# SysML 2 REQ — Zwölf Anforderungen, umgebrochen mit wrapColumns

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ohne Hinweis stehen 12 Anforderungen in einer sehr breiten Zeile. `layout { wrapColumns = 4 }` bricht das Diagramm in drei Zeilen zu je vier Spalten um.

## Diagramm

```kuml
import dev.kuml.sysml2.RequirementPriority
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Platform Requirements") {
    val reqs = listOf(
        "Boot" to "The platform shall boot within 5 seconds.",
        "Update" to "Updates shall install without user interaction.",
        "Rollback" to "A failed update shall roll back automatically.",
        "Telemetry" to "Telemetry shall be opt-in.",
        "Logging" to "Logs shall be kept for 30 days.",
        "Auth" to "Every request shall be authenticated.",
        "Audit" to "Security events shall be audited.",
        "Encrypt" to "Data at rest shall be encrypted.",
        "Backup" to "Backups shall run nightly.",
        "Restore" to "A restore shall complete within one hour.",
        "Scale" to "The platform shall scale to 10 000 users.",
        "Monitor" to "Health shall be monitored continuously.",
    ).mapIndexed { i, (name, text) ->
        requirementDef(name, reqId = "R-${i + 1}", text = text, priority = RequirementPriority.MEDIUM)
    }
    reqDiagram("Platform requirements") {
        reqs.forEach { include(it) }
        layout {
            wrapColumns = 4
        }
    }
}
```

## Ohne Hinweis

Lässt man den `layout { }`-Block weg, ordnet die Engine alle Anforderungen nebeneinander an. Genau dafür gibt es `wrapColumns` (1 bis 32).

## Verwandte Beispiele

- [[46 SysML 2 REQ – Anforderungen mit Gruppen]]

