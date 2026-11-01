// Wave 2E-b fixture: seven action definitions allocated to one block with an actions compartment.
sysml2Model("Allocation") {
    val ctrl = partDef("Steuerung") {
        action("start", "Start")
        action("stop", "Stop")
        action("monitor", "Monitor")
        action("log", "Log")
    }
    val acts = listOf("Start", "Stop", "Monitor", "Log", "Alarm", "Reset", "Calibrate").map { actionDef(it) }
    acts.forEach { allocate(from = it, to = ctrl) }
    bdd("Allocation") {
        include(ctrl)
        acts.forEach { include(it) }
        layout {
            direction = Right
        }
    }
}
