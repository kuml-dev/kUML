---
title: SysML 2 ACT – Notiz an einer Partition
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - sysml2
  - act
  - notiz
status: aktiv
---

# SysML 2 ACT – Notiz an einer Partition

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Eine Notiz an einer Swimlane. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Orders") {
    val customer = activityPartition("Customer")
    val system = activityPartition("OrderSystem")
    val place = actionDef(name = "PlaceOrder", partition = customer)
    val validate = actionDef(name = "ValidateOrder", partition = system)
    controlFlow(name = "p2v", source = place, target = validate)
    comment(text = "The customer is always a registered user.", firstAnchor = customer, id = "nl")
    actDiagram("Orders") {
        include(place)
        include(validate)
    }
}
```
