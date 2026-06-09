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
sysml2Model("ThermostatBoot") {

    // ── Nodes ─────────────────────────────────────────────────────────────────
    val init = initialNode()

    val readSensors = actionDef("ReadSensors", action = "sensors.readAll()")

    val decide = decisionNode("sensors valid?")

    val calibrate = actionDef("Calibrate", action = "calibration.run()")

    val fork = forkNode("bootFork")

    val updateDisplay = actionDef("UpdateDisplay", action = "display.update()")
    val logReady = actionDef("LogReady", action = "log.info('Thermostat ready')")

    val join = joinNode("bootJoin")

    val fin = finalNode()

    val errorAlert = actionDef("ErrorAlert", action = "alert.send('Sensor failure')")

    val flowFin = flowFinalNode()

    // ── Control Flows ─────────────────────────────────────────────────────────
    controlFlow("toRead", init, readSensors)
    controlFlow("toDecide", readSensors, decide)
    controlFlow("validPath", decide, calibrate, guard = "sensorsValid")
    controlFlow("toFork", calibrate, fork)
    controlFlow("forkToDisplay", fork, updateDisplay)
    controlFlow("forkToLog", fork, logReady)
    controlFlow("displayToJoin", updateDisplay, join)
    controlFlow("logToJoin", logReady, join)
    controlFlow("joinToFinal", join, fin)
    controlFlow("errorPath", decide, errorAlert, guard = "not sensorsValid")
    controlFlow("errorToFlowFinal", errorAlert, flowFin)

    // ── Activity Diagram ──────────────────────────────────────────────────────
    actDiagram("Pepela Thermostat — boot calibration") {
        include(init)
        include(readSensors)
        include(decide)
        include(calibrate)
        include(fork)
        include(updateDisplay)
        include(logReady)
        include(join)
        include(fin)
        include(errorAlert)
        include(flowFin)
    }
}
