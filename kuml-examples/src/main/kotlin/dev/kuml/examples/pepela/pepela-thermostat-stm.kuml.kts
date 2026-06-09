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
 * The kUML OCL subset supports integer arithmetic only, so temperatures are
 * whole-number °C values. A 1 °C hysteresis band prevents oscillation:
 *  - Enter Heating:  temperature < targetTemperature - 1  (i.e. ≤ target - 2)
 *  - Leave Heating:  temperature >= targetTemperature
 *  - Enter Cooling:  temperature > targetTemperature + 1  (i.e. ≥ target + 2)
 *  - Leave Cooling:  temperature <= targetTemperature
 */
sysml2Model("Thermostat") {

    // ── States ────────────────────────────────────────────────────────────────
    val initial = stateDef("Initial", isInitial = true)

    val off =
        stateDef(
            "Off",
            entryAction = "relays.allOff()",
        )

    val idle =
        stateDef(
            "Idle",
            entryAction = "display.show('idle')",
        )

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

    // ── Transitions ───────────────────────────────────────────────────────────

    // Initial pseudo-state fires immediately into Off (no trigger needed — initial auto-fire)
    transition("init", initial, off)

    // Power on
    transition("powerOn", off, idle, trigger = "powerOn")

    // Power off from any state
    transition(
        "offFromIdle",
        idle,
        off,
        trigger = "powerOff",
        id = "transition:Idle::Off:powerOff",
    )
    transition(
        "offFromHeating",
        heating,
        off,
        trigger = "powerOff",
        id = "transition:Heating::Off:powerOff",
    )
    transition(
        "offFromCooling",
        cooling,
        off,
        trigger = "powerOff",
        id = "transition:Cooling::Off:powerOff",
    )
    transition(
        "offFromEco",
        eco,
        off,
        trigger = "powerOff",
        id = "transition:Eco::Off:powerOff",
    )

    // Idle → Heating when too cold (tick with temperature payload)
    transition(
        "startHeating",
        idle,
        heating,
        trigger = "tick",
        guard = "event.temperature < event.targetTemperature - 1",
        id = "transition:Idle::Heating",
    )

    // Idle → Cooling when too warm (tick with temperature payload)
    transition(
        "startCooling",
        idle,
        cooling,
        trigger = "tick",
        guard = "event.temperature > event.targetTemperature + 1",
        id = "transition:Idle::Cooling",
    )

    // Idle → Eco on explicit trigger
    transition("enterEco", idle, eco, trigger = "ecoMode")

    // Heating → Idle once target reached (tick with temperature payload)
    transition(
        "heatDone",
        heating,
        idle,
        trigger = "tick",
        guard = "event.temperature >= event.targetTemperature",
        id = "transition:Heating::Idle",
    )

    // Cooling → Idle once target reached (tick with temperature payload)
    transition(
        "coolDone",
        cooling,
        idle,
        trigger = "tick",
        guard = "event.temperature <= event.targetTemperature",
        id = "transition:Cooling::Idle",
    )

    // Eco → Idle on explicit trigger
    transition("exitEco", eco, idle, trigger = "normalMode")

    // ── State Transition Diagram ──────────────────────────────────────────────
    stmDiagram("Pepela Thermostat — temperature control") {
        include(initial)
        include(off)
        include(idle)
        include(heating)
        include(cooling)
        include(eco)
    }
}
