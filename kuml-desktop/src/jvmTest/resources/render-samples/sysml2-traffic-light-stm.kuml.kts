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
