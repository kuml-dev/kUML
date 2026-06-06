@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Traffic Light — SysML 2 State Transition Diagram example (V2.0.9 MVP).
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
sysml2Model("TrafficLight") {

    // ── States ────────────────────────────────────────────────────────────
    val initial = stateDef("Initial", isInitial = true)
    val red =
        stateDef(
            "Red",
            entryAction = "switchLights('red')",
            exitAction = "logTransition('red')",
        )
    val green =
        stateDef(
            "Green",
            entryAction = "switchLights('green')",
            doAction = "tickTimer()",
        )
    val yellow =
        stateDef(
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
