---
title: UML-Klasse – Dichte Kanten und Labels
date: 2026-10-31
tags:
  - kUML
  - beispiel
  - uml
  - klassendiagramm
  - label
status: aktiv
---

# UML-Klasse – Dichte Kanten und Labels

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Sechs Assoziationen laufen auf eine Hub-Klasse. Rollen und Multiplizitäten an beiden Enden, dazu ein Abhängigkeitsname. Der Label-Platzierer hält sie von Knoten, anderen Labels und fremden Kanten fern. (PAL-Welle 2B-b)

## Diagramm

```kuml
classDiagram(name = "Dense Hub") {
    val hub = classOf(name = "Account") {
        attribute(name = "id", type = "UUID")
    }
    val satellites = listOf("Customer", "Branch", "Card", "Statement", "Limit", "Audit").map { n ->
        classOf(name = n) { attribute(name = "id", type = "UUID") }
    }
    association(source = satellites[0], target = hub) {
        source { multiplicity(spec = "1..*"); role = "holder" }
        target { multiplicity(spec = "0..*"); role = "accounts" }
    }
    association(source = satellites[1], target = hub) {
        source { multiplicity(spec = "0..1"); role = "home" }
        target { multiplicity(spec = "0..*"); role = "managed" }
    }
    association(source = satellites[2], target = hub) {
        source { multiplicity(spec = "0..*"); role = "cards" }
        target { multiplicity(spec = "1"); role = "account" }
    }
    association(source = satellites[3], target = hub) {
        source { multiplicity(spec = "0..*"); role = "statements" }
        target { multiplicity(spec = "1"); role = "of" }
    }
    association(source = satellites[4], target = hub) {
        source { multiplicity(spec = "0..1"); role = "limit" }
        target { multiplicity(spec = "1"); role = "limited" }
    }
    association(source = satellites[5], target = hub) {
        source { multiplicity(spec = "0..*"); role = "entries" }
        target { multiplicity(spec = "1"); role = "audited" }
    }
    dependency(client = satellites[0], supplier = satellites[1], name = "refers to")
}
```
