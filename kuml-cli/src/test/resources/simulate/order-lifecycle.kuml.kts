// Simple state machine for `kuml simulate` CLI tests.
// Plain transitions without OCL guards so the basic happy path runs.

stateDiagram(name = "OrderLifecycle") {

    val init = initialState()
    val draft = state(name = "Draft") { entry = "validate()" }
    val confirmed = state(name = "Confirmed") { entry = "reserveStock()" }
    val done = finalState(name = "Done")

    transition(source = init, target = draft)
    transition(source = draft, target = confirmed) { trigger = "confirm()"; effect = "log()" }
    transition(source = confirmed, target = done) { trigger = "deliver()" }
}
