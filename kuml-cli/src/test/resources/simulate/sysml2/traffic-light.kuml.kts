@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

// V2.0.17 — SysML 2 STM simulate smoke test fixture.
// Mirrors the example at kuml-examples/.../sysml2/traffic-light-stm.kuml.kts
// but trimmed to the bits the test cares about so the test resource stays
// hermetic.
sysml2Model("TrafficLight") {
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

    transition("init", initial, red)
    transition("redToGreen", red, green, trigger = "timer60s")
    transition("greenToYellow", green, yellow, trigger = "timer45s")
    transition("yellowToRed", yellow, red, trigger = "timer5s")
    transition(
        "powerOff",
        red,
        off,
        trigger = "powerOff",
        effect = "shutdownLights()",
    )

    stmDiagram("TrafficLight — phase cycle") {
        include(initial)
        include(red)
        include(green)
        include(yellow)
        include(off)
    }
}
