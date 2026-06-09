@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Pepela Thermostat Boot Activity — CLI smoke test fixture (V2.0.19).
 *
 * Trimmed mirror of kuml-examples/.../pepela/pepela-thermostat-flow.kuml.kts.
 *
 * Happy path: sensorsValid=true
 *   Initial → ReadSensors → Decision[valid] → Calibrate
 *     → Fork → [UpdateDisplay, LogReady] → Join → Final(ActivityTerminated)
 */
sysml2Model("ThermostatBoot") {

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
