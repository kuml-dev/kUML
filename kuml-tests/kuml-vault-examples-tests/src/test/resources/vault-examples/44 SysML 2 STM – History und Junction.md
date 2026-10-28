---
title: SysML 2 STM – History und Junction
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - stm
  - zustandsmaschine
  - history
  - junction
status: aktiv
---

# SysML 2 STM — History-Zustand und Junction

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Eine Waschmaschine: `On` ist ein zusammengesetzter Zustand mit den Phasen `Washing` und `Spinning`. Wird sie ausgeschaltet und wieder eingeschaltet, setzt der **Deep-History-Zustand** `H*` die Phase dort fort, wo sie unterbrochen wurde. Eine **Junction** wählt beim Start statisch das Programm anhand von Guards.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Waschmaschine") {

    val initial = stateDef(name = "Initial", isInitial = true)
    val off = stateDef(name = "Off")

    // ── On: zusammengesetzter Zustand mit History ────────────────────────
    val on = stateDef(name = "On")
    val onInit = stateDef(name = "OnInit", isInitial = true, parent = on)
    val washing = stateDef(name = "Washing", parent = on)
    val spinning = stateDef(name = "Spinning", parent = on)
    val hist = history(name = "H", deep = true, parent = on)

    // ── Junction: Programmwahl nach Guard ─────────────────────────────────
    val choose = junction(name = "Program")
    val eco = stateDef(name = "EcoWash")
    val normal = stateDef(name = "NormalWash")

    // ── Übergänge ─────────────────────────────────────────────────────────
    transition(name = "init", source = initial, target = off)
    transition(name = "powerOn", source = off, target = hist, trigger = "powerOn")
    transition(name = "powerOff", source = on, target = off, trigger = "powerOff")
    transition(name = "onInit", source = onInit, target = washing)
    transition(name = "spin", source = washing, target = spinning, trigger = "spin")
    transition(name = "wash", source = spinning, target = washing, trigger = "wash")
    transition(name = "selectProgram", source = off, target = choose, trigger = "start")
    transition(name = "ecoRun", source = choose, target = eco, guard = "vars.load < 3")
    transition(name = "normalRun", source = choose, target = normal, guard = "vars.load >= 3")

    stmDiagram(name = "Waschmaschine") {
        include(state = initial)
        include(state = off)
        include(state = on)
        include(state = choose)
        include(state = eco)
        include(state = normal)
    }
}
```

## Was gezeigt wird

| Konstrukt | Verhalten in der Simulation |
|---|---|
| `history(name = "H", deep = true, parent = on)` | Beim Verlassen von `On` wird die aktive Konfiguration gemerkt, `powerOn` stellt sie wieder her; ohne Speicher gilt der Anfangszustand |
| `junction(name = …)` | Statische Kette: Guards werden **vor** dem Verlassen des Quellzustands ausgewertet, alle Effects laufen in einem Schritt |
| Kein gültiger Pfad | Der Übergang feuert nicht, die Spur enthält `NotFired` mit dem Grund |

Verwandt: [[43 SysML 2 STM – Regionen mit Fork-Join]]
