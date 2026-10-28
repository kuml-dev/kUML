sysml2Model("Machine") {
    val start = stateDef("Start", isInitial = true)
    val off = stateDef("Off")
    val on = stateDef("On", entryAction = "powerUp()", exitAction = "powerDown()")
    val idle = stateDef("Idle", parent = on)
    val running = stateDef("Running", parent = on)
    val resume = history("H", parent = on)
    val done = stateDef("Done", isFinal = true)

    transition("init", start, off)
    transition("resume", off, resume, trigger = "switchOn")
    transition("default", resume, idle)
    transition("go", idle, running, trigger = "start")
    transition("halt", running, idle, trigger = "stop")
    transition("switchOff", on, off, trigger = "switchOff")
    transition("shutdown", off, done, trigger = "shutdown")

    stmDiagram("Machine - composite state with history") {
        include(start)
        include(off)
        include(on)
        include(done)
    }
}
