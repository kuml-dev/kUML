// sandbox-clean.kuml.kts — no sandbox policy violations
stateDiagram(name = "CleanMachine") {
    val init = initialState()
    val idle = state(name = "Idle") { entry = "count = 0" }
    val active = state(name = "Active") { entry = "count = 1" }
    val done = finalState(name = "Done")

    transition(source = init, target = idle)
    transition(source = idle, target = active) { trigger = "start()" }
    transition(source = active, target = done) { trigger = "stop()" }
}
