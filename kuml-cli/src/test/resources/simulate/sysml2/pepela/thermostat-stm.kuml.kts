@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Pepela Thermostat STM — CLI smoke test fixture (V2.0.19).
 *
 * Trimmed mirror of kuml-examples/.../pepela/pepela-thermostat-stm.kuml.kts.
 * The test cares about state-sequence shape, not diagram aesthetics.
 *
 * Event sequence (see thermostat-stm.events.json):
 *   powerOn → Off→Idle
 *   tick(16,21) → Idle→Heating  (16 < 21-1 = 20 ✓)
 *   tick(19,21) → stays Heating (19 < 21 ✓, 19 >= 21? ✗)
 *   tick(21,21) → Heating→Idle  (21 >= 21 ✓)
 *   tick(24,21) → Idle→Cooling  (24 > 21+1 = 22 ✓)
 *   tick(21,21) → Cooling→Idle  (21 <= 21 ✓)
 *   ecoMode    → Idle→Eco
 *   normalMode → Eco→Idle
 *   powerOff   → Idle→Off
 */
sysml2Model("Thermostat") {

    val initial = stateDef("Initial", isInitial = true)
    val off = stateDef("Off", entryAction = "relays.allOff()")
    val idle = stateDef("Idle", entryAction = "display.show('idle')")
    val heating =
        stateDef(
            "Heating",
            entryAction = "relay.heat(true)",
            exitAction = "relay.heat(false)",
        )
    val cooling =
        stateDef(
            "Cooling",
            entryAction = "relay.cool(true)",
            exitAction = "relay.cool(false)",
        )
    val eco =
        stateDef(
            "Eco",
            entryAction = "display.show('eco')",
            doAction = "setTargetTemp(18)",
        )

    transition("init", initial, off)
    transition("powerOn", off, idle, trigger = "powerOn")
    transition("offFromIdle", idle, off, trigger = "powerOff", id = "transition:Idle::Off:powerOff")
    transition("offFromHeating", heating, off, trigger = "powerOff", id = "transition:Heating::Off:powerOff")
    transition("offFromCooling", cooling, off, trigger = "powerOff", id = "transition:Cooling::Off:powerOff")
    transition("offFromEco", eco, off, trigger = "powerOff", id = "transition:Eco::Off:powerOff")
    transition(
        "startHeating",
        idle,
        heating,
        trigger = "tick",
        guard = "event.temperature < event.targetTemperature - 1",
        id = "transition:Idle::Heating",
    )
    transition(
        "startCooling",
        idle,
        cooling,
        trigger = "tick",
        guard = "event.temperature > event.targetTemperature + 1",
        id = "transition:Idle::Cooling",
    )
    transition("enterEco", idle, eco, trigger = "ecoMode")
    transition(
        "heatDone",
        heating,
        idle,
        trigger = "tick",
        guard = "event.temperature >= event.targetTemperature",
        id = "transition:Heating::Idle",
    )
    transition(
        "coolDone",
        cooling,
        idle,
        trigger = "tick",
        guard = "event.temperature <= event.targetTemperature",
        id = "transition:Cooling::Idle",
    )
    transition("exitEco", eco, idle, trigger = "normalMode")

    stmDiagram("Pepela Thermostat — temperature control") {
        include(initial)
        include(off)
        include(idle)
        include(heating)
        include(cooling)
        include(eco)
    }
}
