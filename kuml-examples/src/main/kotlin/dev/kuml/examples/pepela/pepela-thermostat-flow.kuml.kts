@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Pepela Smart Home — Thermostat Boot/Calibration Activity (V2.0.19 showcase)
 *
 * Models the power-on self-test and sensor calibration workflow that runs
 * once when the thermostat starts up. Complements the STM example by showing
 * the activity (token-flow) runtime side by side with the state-machine runtime.
 *
 * Runnable via:
 *   kuml simulate pepela-thermostat-flow.kuml.kts \
 *     --events pepela-thermostat-flow-events.json \
 *     --out /tmp/thermostat-flow-trace.json
 *
 * Flow:
 *   Initial → ReadSensors → Decision "sensors valid?"
 *     ├── [valid]   → Calibrate → Fork:
 *     │                 ├── UpdateDisplay
 *     │                 └── LogReady
 *     │               → Join → ActivityFinal
 *     └── [!valid]  → ErrorAlert → FlowFinal
 *
 * The guard key `sensorsValid` is read from the first event payload so the
 * same script can be exercised for both the happy path and the error path
 * simply by changing the events file.
 */
sysml2Model(name = "ThermostatBoot") {

    // ── Nodes ─────────────────────────────────────────────────────────────────
    val init = initialNode()

    val readSensors = actionDef(name = "ReadSensors", action = "sensors.readAll()")

    val decide = decisionNode(name = "sensors valid?")

    val calibrate = actionDef(name = "Calibrate", action = "calibration.run()")

    val fork = forkNode(name = "bootFork")

    val updateDisplay = actionDef(name = "UpdateDisplay", action = "display.update()")
    val logReady = actionDef(name = "LogReady", action = "log.info('Thermostat ready')")

    val join = joinNode(name = "bootJoin")

    val fin = finalNode()

    val errorAlert = actionDef(name = "ErrorAlert", action = "alert.send('Sensor failure')")

    val flowFin = flowFinalNode()

    // ── Control Flows ─────────────────────────────────────────────────────────
    controlFlow(name = "toRead", source = init, target = readSensors)
    controlFlow(name = "toDecide", source = readSensors, target = decide)
    controlFlow(name = "validPath", source = decide, target = calibrate, guard = "sensorsValid")
    controlFlow(name = "toFork", source = calibrate, target = fork)
    controlFlow(name = "forkToDisplay", source = fork, target = updateDisplay)
    controlFlow(name = "forkToLog", source = fork, target = logReady)
    controlFlow(name = "displayToJoin", source = updateDisplay, target = join)
    controlFlow(name = "logToJoin", source = logReady, target = join)
    controlFlow(name = "joinToFinal", source = join, target = fin)
    controlFlow(name = "errorPath", source = decide, target = errorAlert, guard = "not sensorsValid")
    controlFlow(name = "errorToFlowFinal", source = errorAlert, target = flowFin)

    // ── Activity Diagram ──────────────────────────────────────────────────────
    actDiagram(name = "Pepela Thermostat — boot calibration") {
        include(node = init)
        include(node = readSensors)
        include(node = decide)
        include(node = calibrate)
        include(node = fork)
        include(node = updateDisplay)
        include(node = logReady)
        include(node = join)
        include(node = fin)
        include(node = errorAlert)
        include(node = flowFin)
    }
}
