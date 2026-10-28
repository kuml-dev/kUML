---
title: SysML 2 STM – Timer (Ampel mit after)
date: 2026-10-28
tags:
  - kUML
  - beispiel
  - sysml2
  - stm
  - zustandsmaschine
  - timer
status: aktiv
---

# SysML 2 STM — Timer-Übergänge (`after` und `at`)

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]] · Vorgänger [[04 SysML 2 STM – Traffic Light]]

> [!info] Worum es geht
> Die Ampel aus [[04 SysML 2 STM – Traffic Light]], diesmal mit **echten Zeit-Triggern**: `timer = after(duration = 30.seconds)` feuert 30 Sekunden nach dem Betreten des Quellzustands, `timer = at(iso = "…")` zu einem absoluten UTC-Zeitpunkt. Der Simulator rechnet mit einer virtuellen Uhr: `kuml simulate … --start-time 2026-12-24T21:59:00Z` mit den Ereignissen `+next` oder `+30s`.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model
import kotlin.time.Duration.Companion.seconds

sysml2Model(name = "TimerAmpel") {

    // ── Zustände ──────────────────────────────────────────────────────────
    val initial = stateDef(name = "Initial", isInitial = true)
    val red = stateDef(name = "Red", entryAction = "switchLights('red')")
    val green = stateDef(name = "Green", entryAction = "switchLights('green')")
    val yellow = stateDef(name = "Yellow", entryAction = "switchLights('yellow')")
    val night = stateDef(name = "NightBlink", entryAction = "blinkYellow()")
    val off = stateDef(name = "Off", isFinal = true)

    // ── Übergänge: Zeit-Trigger statt Ereignisnamen ───────────────────────
    transition(name = "init", source = initial, target = red)
    transition(name = "redToGreen", source = red, target = green, timer = after(duration = 30.seconds))
    transition(name = "greenToYellow", source = green, target = yellow, timer = after(duration = 45.seconds))
    transition(name = "yellowToRed", source = yellow, target = red, timer = after(duration = 5.seconds))
    transition(name = "nightMode", source = red, target = night, timer = at(iso = "2026-12-24T22:00:00Z"))
    transition(name = "daybreak", source = night, target = red, trigger = "daybreak")
    transition(
        name = "powerOff",
        source = red,
        target = off,
        trigger = "powerOff",
        guard = "!emergency",
        effect = "shutdownLights()",
    )

    // ── Diagramm ──────────────────────────────────────────────────────────
    stmDiagram(name = "Timer-Ampel") {
        include(state = initial)
        include(state = red)
        include(state = green)
        include(state = yellow)
        include(state = night)
        include(state = off)
    }
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `timer = after(duration = …)` | Relativer Timer, scharf geschaltet beim Betreten des Quellzustands, verworfen beim Verlassen |
| `timer = at(iso = "…Z")` | Absoluter Zeitpunkt (UTC, Millisekunden-Genauigkeit); liegt er in der Vergangenheit, feuert er beim nächsten Vorspulen genau einmal |
| `trigger` und `timer` zugleich | Fehler 124 des Checkers: ein Übergang hat entweder Ereignis oder Zeit |
| Kantenbeschriftung | `after(30s)`, `at(2026-12-24T22:00:00Z)` |

## Simulation

```bash
kuml simulate ampel.kuml.kts events.json --start-time 2026-12-24T21:59:00Z
# events.json: [{"name": "+next"}, {"name": "+next"}]   (springt von Timer zu Timer)
```

Verwandt: [[43 SysML 2 STM – Regionen mit Fork-Join]], [[45 SysML 2 STM – Prosa-Guards]]
