---
title: SysML 2 STM – Traffic Light
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - sysml2
  - stm
  - zustandsmaschine
status: aktiv
---

# SysML 2 State Transition Diagram — Traffic Light

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Klassische **Ampel-Zustandsmaschine** als SysML 2 STM: ein Initial-Pseudo-State führt in den `Red`-Zustand, daraus zyklisches Phasen-Verhalten Red → Green → Yellow → Red durch Timer-Trigger und schließlich ein `powerOff`-Übergang mit Guard und Effect in den Final-Pseudo-State.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "TrafficLight") {

    // ── States ────────────────────────────────────────────────────────────
    val initial = stateDef(name = "Initial", isInitial = true)
    val red = stateDef(
        name = "Red",
        entryAction = "switchLights('red')",
        exitAction = "logTransition('red')",
    )
    val green = stateDef(
        name = "Green",
        entryAction = "switchLights('green')",
        doAction = "tickTimer()",
    )
    val yellow = stateDef(
        name = "Yellow",
        entryAction = "switchLights('yellow')",
    )
    val off = stateDef(name = "Off", isFinal = true)

    // ── Transitions ───────────────────────────────────────────────────────
    transition(name = "init", source = initial, target = red)
    transition(name = "redToGreen", source = red, target = green, trigger = "timer60s")
    transition(name = "greenToYellow", source = green, target = yellow, trigger = "timer45s")
    transition(name = "yellowToRed", source = yellow, target = red, trigger = "timer5s")
    transition(
        name = "powerOff",
        source = red,
        target = off,
        trigger = "powerOff",
        guard = "!emergency",
        effect = "shutdownLights()",
    )

    // ── State Transition Diagram ─────────────────────────────────────────
    stmDiagram(name = "TrafficLight — phase cycle") {
        include(state = initial)
        include(state = red)
        include(state = green)
        include(state = yellow)
        include(state = off)
    }
}
```

## State-Typen im Überblick

| Typ | Wie deklariert | Darstellung |
|---|---|---|
| **Initial-Pseudo-State** | `stateDef(name = "Initial", isInitial = true)` | Gefüllter Kreis |
| **Regulärer State** | `stateDef(name = "Red", entryAction = …, exitAction = …)` | Abgerundetes Rechteck mit `entry`/`exit`/`do`-Kompartments |
| **Final-Pseudo-State** | `stateDef(name = "Off", isFinal = true)` | Donut (Kreis im Kreis) |

## Action-Slots

Jeder reguläre State kann drei Verhaltens-Slots tragen:

- **`entryAction`** — wird ausgeführt, sobald der State betreten wird
- **`exitAction`** — wird ausgeführt, sobald der State verlassen wird
- **`doAction`** — läuft *während* der State aktiv ist (typisch in einer Schleife oder als Timer-Tick)

Heute sind das **rohe Strings** — getestet werden sie nicht. Mit dem typisierten Expression-AST aus V2.0.20 werden sie Type-Checked.

## Transitions

`transition(name, source, target, trigger?, guard?, effect?)` — alle drei nach `target` sind optional:

| Parameter | Bedeutung |
|---|---|
| `trigger` | Event-Name, der den Übergang auslöst (z. B. `"timer60s"`) |
| `guard` | Boolescher Ausdruck — Übergang nur wenn `true` (z. B. `"!emergency"`) |
| `effect` | Action, die *während* des Übergangs läuft (z. B. `"shutdownLights()"`) |

## Live-Simulation

Dieses Modell ist **ausführbar** über `kuml simulate`:

```bash
kuml simulate traffic-light-stm.kuml.kts events.json --out trace.json
```

mit `events.json`:

```json
[
  {"name": "timer60s"},
  {"name": "timer45s"},
  {"name": "timer5s"},
  {"name": "powerOff"}
]
```

Resultat: deterministischer Trace `Initial → Red → Green → Yellow → Red → Off`. Siehe V2.0.17 in [[02 Projekte/kUML V2.0]].

## Verwandte Beispiele

- [[03 SysML 2 BDD – Hybrid Vehicle]] — strukturelles Modell statt Behaviour
- [[06 SysML 2 SEQ – Login Flow]] — Sequence-Diagramm in SysML 2

## Verwandte Vault-Notizen

- [[03 Bereiche/kUML/ADR/ADR-0007 Executable Behaviour Runtime]]
- [[02 Projekte/kUML V2.0#Executable Behaviour Runtime (Vollausbau)]]
