// sandbox-violations.kuml.kts — contains sandbox policy violations (disallowed fn on strict)
stateDiagram(name = "ViolationMachine") {
    val init = initialState()
    val idle = state(name = "Idle") { entry = "log.info('hello')" }
    val done = finalState(name = "Done")

    transition(source = init, target = idle)
    transition(source = idle, target = done) { trigger = "stop()" }
}
