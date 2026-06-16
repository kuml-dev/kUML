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

sysml2Model("TrafficLight") {

    // ── States ────────────────────────────────────────────────────────────
    val initial = stateDef("Initial", isInitial = true)
    val red = stateDef(
        "Red",
        entryAction = "switchLights('red')",
        exitAction = "logTransition('red')",
    )
    val green = stateDef(
        "Green",
        entryAction = "switchLights('green')",
        doAction = "tickTimer()",
    )
    val yellow = stateDef(
        "Yellow",
        entryAction = "switchLights('yellow')",
    )
    val off = stateDef("Off", isFinal = true)

    // ── Transitions ───────────────────────────────────────────────────────
    transition("init", initial, red)
    transition("redToGreen", red, green, trigger = "timer60s")
    transition("greenToYellow", green, yellow, trigger = "timer45s")
    transition("yellowToRed", yellow, red, trigger = "timer5s")
    transition(
        "powerOff",
        red,
        off,
        trigger = "powerOff",
        guard = "!emergency",
        effect = "shutdownLights()",
    )

    // ── State Transition Diagram ─────────────────────────────────────────
    stmDiagram("TrafficLight — phase cycle") {
        include(initial)
        include(red)
        include(green)
        include(yellow)
        include(off)
    }
}
```

## State-Typen im Überblick

| Typ | Wie deklariert | Darstellung |
|---|---|---|
| **Initial-Pseudo-State** | `stateDef("Initial", isInitial = true)` | Gefüllter Kreis |
| **Regulärer State** | `stateDef("Red", entryAction = …, exitAction = …)` | Abgerundetes Rechteck mit `entry`/`exit`/`do`-Kompartments |
| **Final-Pseudo-State** | `stateDef("Off", isFinal = true)` | Donut (Kreis im Kreis) |

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
