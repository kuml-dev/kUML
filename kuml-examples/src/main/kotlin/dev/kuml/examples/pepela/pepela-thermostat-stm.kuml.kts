@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Pepela Smart Home — Thermostat State Machine (V2.0.19 showcase)
 *
 * Models the heating/cooling control logic of a smart thermostat.
 * First end-user-facing demo of the kUML executable behaviour runtime.
 *
 * Runnable via:
 *   kuml simulate pepela-thermostat-stm.kuml.kts \
 *     --events pepela-thermostat-stm-events.json \
 *     --out /tmp/thermostat-trace.json
 *
 * States: Off, Idle, Heating, Cooling, Eco
 *
 * Temperature-based transitions are driven by `tick` events that carry
 * `temperature` and `targetTemperature` in their JSON payload:
 *   {"name":"tick","payload":{"temperature":16,"targetTemperature":21}}
 *
 * kUML's OCL expression language supports both Integer and Real arithmetic, but this
 * example sticks to whole-number °C values. A 1 °C hysteresis band prevents oscillation:
 *  - Enter Heating:  temperature < targetTemperature - 1  (i.e. ≤ target - 2)
 *  - Leave Heating:  temperature >= targetTemperature
 *  - Enter Cooling:  temperature > targetTemperature + 1  (i.e. ≥ target + 2)
 *  - Leave Cooling:  temperature <= targetTemperature
 */
sysml2Model(name = "Thermostat") {

    // ── States ────────────────────────────────────────────────────────────────
    val initial = stateDef(name = "Initial", isInitial = true)

    val off =
        stateDef(
            name = "Off",
            entryAction = "relays.allOff()",
        )

    val idle =
        stateDef(
            name = "Idle",
            entryAction = "display.show('idle')",
        )

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

    // ── Transitions ───────────────────────────────────────────────────────────

    // Initial pseudo-state fires immediately into Off (no trigger needed — initial auto-fire)
    transition(name = "init", source = initial, target = off)

    // Power on
    transition(name = "powerOn", source = off, target = idle, trigger = "powerOn")

    // Power off from any state
    transition(
        name = "offFromIdle",
        source = idle,
        target = off,
        trigger = "powerOff",
        id = "transition:Idle::Off:powerOff",
    )
    transition(
        name = "offFromHeating",
        source = heating,
        target = off,
        trigger = "powerOff",
        id = "transition:Heating::Off:powerOff",
    )
    transition(
        name = "offFromCooling",
        source = cooling,
        target = off,
        trigger = "powerOff",
        id = "transition:Cooling::Off:powerOff",
    )
    transition(
        name = "offFromEco",
        source = eco,
        target = off,
        trigger = "powerOff",
        id = "transition:Eco::Off:powerOff",
    )

    // Idle → Heating when too cold (tick with temperature payload)
    transition(
        name = "startHeating",
        source = idle,
        target = heating,
        trigger = "tick",
        guard = "event.temperature < event.targetTemperature - 1",
        id = "transition:Idle::Heating",
    )

    // Idle → Cooling when too warm (tick with temperature payload)
    transition(
        name = "startCooling",
        source = idle,
        target = cooling,
        trigger = "tick",
        guard = "event.temperature > event.targetTemperature + 1",
        id = "transition:Idle::Cooling",
    )

    // Idle → Eco on explicit trigger
    transition(name = "enterEco", source = idle, target = eco, trigger = "ecoMode")

    // Heating → Idle once target reached (tick with temperature payload)
    transition(
        name = "heatDone",
        source = heating,
        target = idle,
        trigger = "tick",
        guard = "event.temperature >= event.targetTemperature",
        id = "transition:Heating::Idle",
    )

    // Cooling → Idle once target reached (tick with temperature payload)
    transition(
        name = "coolDone",
        source = cooling,
        target = idle,
        trigger = "tick",
        guard = "event.temperature <= event.targetTemperature",
        id = "transition:Cooling::Idle",
    )

    // Eco → Idle on explicit trigger
    transition(name = "exitEco", source = eco, target = idle, trigger = "normalMode")

    // ── State Transition Diagram ──────────────────────────────────────────────
    stmDiagram(name = "Pepela Thermostat — temperature control") {
        include(state = initial)
        include(state = off)
        include(state = idle)
        include(state = heating)
        include(state = cooling)
        include(state = eco)
    }
}
