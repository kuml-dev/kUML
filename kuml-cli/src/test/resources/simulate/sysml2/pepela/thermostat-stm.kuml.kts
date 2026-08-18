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
sysml2Model(name = "Thermostat") {

    val initial = stateDef(name = "Initial", isInitial = true)
    val off = stateDef(name = "Off", entryAction = "relays.allOff()")
    val idle = stateDef(name = "Idle", entryAction = "display.show('idle')")
    val heating =
        stateDef(
            name = "Heating",
            entryAction = "relay.heat(true)",
            exitAction = "relay.heat(false)",
        )
    val cooling =
        stateDef(
            name = "Cooling",
            entryAction = "relay.cool(true)",
            exitAction = "relay.cool(false)",
        )
    val eco =
        stateDef(
            name = "Eco",
            entryAction = "display.show('eco')",
            doAction = "setTargetTemp(18)",
        )

    transition(name = "init", source = initial, target = off)
    transition(name = "powerOn", source = off, target = idle, trigger = "powerOn")
    transition(name = "offFromIdle", source = idle, target = off, trigger = "powerOff", id = "transition:Idle::Off:powerOff")
    transition(name = "offFromHeating", source = heating, target = off, trigger = "powerOff", id = "transition:Heating::Off:powerOff")
    transition(name = "offFromCooling", source = cooling, target = off, trigger = "powerOff", id = "transition:Cooling::Off:powerOff")
    transition(name = "offFromEco", source = eco, target = off, trigger = "powerOff", id = "transition:Eco::Off:powerOff")
    transition(
        name = "startHeating",
        source = idle,
        target = heating,
        trigger = "tick",
        guard = "event.temperature < event.targetTemperature - 1",
        id = "transition:Idle::Heating",
    )
    transition(
        name = "startCooling",
        source = idle,
        target = cooling,
        trigger = "tick",
        guard = "event.temperature > event.targetTemperature + 1",
        id = "transition:Idle::Cooling",
    )
    transition(name = "enterEco", source = idle, target = eco, trigger = "ecoMode")
    transition(
        name = "heatDone",
        source = heating,
        target = idle,
        trigger = "tick",
        guard = "event.temperature >= event.targetTemperature",
        id = "transition:Heating::Idle",
    )
    transition(
        name = "coolDone",
        source = cooling,
        target = idle,
        trigger = "tick",
        guard = "event.temperature <= event.targetTemperature",
        id = "transition:Cooling::Idle",
    )
    transition(name = "exitEco", source = eco, target = idle, trigger = "normalMode")

    stmDiagram(name = "Pepela Thermostat — temperature control") {
        include(initial)
        include(off)
        include(idle)
        include(heating)
        include(cooling)
        include(eco)
    }
}
