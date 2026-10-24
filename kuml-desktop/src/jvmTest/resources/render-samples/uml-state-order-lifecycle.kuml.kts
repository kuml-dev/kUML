// uml-state-order-lifecycle.kuml.kts — auto-synced from vault example
// Source of truth: 03 Bereiche/kUML/Beispiele/18 UML State Machine – Order Lifecycle.md
// Do not edit by hand; update the vault note and re-run the sync.

stateDiagram(name = "Order Lifecycle") {
    val start = initialState()
    val draft = state(name = "Draft") {
        entry = "validate()"
    }
    val confirmed = state(name = "Confirmed")
    val processing = compositeState(name = "Processing") {
        state(name = "Picking")
        state(name = "Packing")
    }
    val shipped = state(name = "Shipped")
    val cancelled = finalState(name = "Cancelled")
    val done = finalState(name = "Done")

    transition(start, draft)
    transition(draft, confirmed) {
        trigger = "confirm()"
        guard = "[valid]"
    }
    transition(draft, cancelled) {
        trigger = "cancel()"
    }
    transition(confirmed, processing) {
        trigger = "process()"
    }
    transition(processing, shipped) {
        trigger = "ship()"
        effect = "notifyCustomer()"
    }
    transition(shipped, done)
}
