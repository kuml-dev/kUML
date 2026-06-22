@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Traffic Light — SysML 2 State Transition Diagram example (V2.0.9 MVP, V2.0.17 runtime).
 *
 * **V2.0.17 update — runnable via `kuml simulate`.** This script is now
 * runnable end-to-end through the Behaviour-Runtime via
 * [dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter]:
 *
 * ```
 * kuml simulate traffic-light-stm.kuml.kts events.json --out trace.json
 * ```
 *
 * with `events.json` containing a sequence like
 * `[{"name":"timer60s"}, {"name":"timer45s"}, {"name":"timer5s"}]` to drive
 * the Red → Green → Yellow → Red cycle, optionally followed by
 * `{"name":"powerOff"}` to land in the `Off` final state. The adapter
 * translates `StateDefinition` + `TransitionUsage` to the existing
 * `UmlStateMachine` runtime input shape, so the trace output format matches
 * the UML simulate path bit-for-bit.
 *
 * Illustriert die V2.0.9-Oberfläche end-to-end:
 *  - StateDefinitions als Knoten:
 *    - Initial pseudo-state (`isInitial = true`, gefüllter Kreis)
 *    - Reguläre Zustände `Red`, `Green`, `Yellow` (abgerundete Boxen)
 *    - Final pseudo-state (`isFinal = true`, Donut)
 *  - Action-Slots (`entryAction`, `exitAction`, `doAction`) auf den
 *    regulären Zuständen — heute als rohe Strings (V2.x typed action AST).
 *  - TransitionUsages mit `trigger / [guard] / effect`, die der Bridge aus
 *    `model.usages` zieht und automatisch in das STM-Diagramm einfügt,
 *    sobald beide Endpunkte sichtbar sind. Transitionen leben bewusst auf
 *    dem Modell, nicht auf dem Diagramm — die zukünftige
 *    Behaviour-Runtime-Welle braucht sie zur Laufzeit.
 *
 * Domain: eine klassische Ampel-Phase mit drei farbigen Zuständen, einer
 * "Power-Off"-Erweiterung in den Final-Zustand und einer Initial-Transition
 * in `Red` als Reset-Verhalten.
 *
 * Out of V2.0.9 scope (siehe Wave-Plan):
 *  - Composite / orthogonal / history states
 *  - Fork / join pseudo-states
 *  - `trigger [guard] / effect` Edge-Labels (kein Konsument im MVP, weil
 *    die synthetische `KumlDiagram`-Hülle keine `UmlRelationship`-Elemente
 *    für TransitionUsages hat)
 *  - Live Behaviour-Runtime-Hookup (separate "Executable Behaviour Runtime"
 *    Welle)
 *  - Typed action AST (heute: rohe Strings)
 */
sysml2Model(name = "TrafficLight") {

    // ── States ────────────────────────────────────────────────────────────
    val initial = stateDef(name = "Initial", isInitial = true)
    val red =
        stateDef(
            name = "Red",
            entryAction = "switchLights('red')",
            exitAction = "logTransition('red')",
        )
    val green =
        stateDef(
            name = "Green",
            entryAction = "switchLights('green')",
            doAction = "tickTimer()",
        )
    val yellow =
        stateDef(
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
    stmDiagram("TrafficLight — phase cycle") {
        include(initial)
        include(red)
        include(green)
        include(yellow)
        include(off)
    }
}
