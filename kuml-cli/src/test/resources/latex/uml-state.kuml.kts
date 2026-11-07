stateDiagram(name = "Order Lifecycle") {
    val start = initialState()
    val draft = state(name = "Draft") {
        entry = "validate()"
    }
    val confirmed = state(name = "Confirmed") {
        entry = "lockInventory()"
        exit = "releaseInventory()"
    }
    val paymentCheck = choice(name = "PaymentOK?")

    // Substates + History-Pseudostate werden im compositeState-Block deklariert
    lateinit var resume: UmlPseudostate
    lateinit var picking: UmlState
    lateinit var packing: UmlState
    val processing = compositeState(name = "Processing") {
        // History-Pseudostate: bei Rückkehr in "Processing" wird der zuletzt aktive Substate wiederhergestellt
        resume = shallowHistory(name = "H")
        picking = state(name = "Picking") {
            doActivity = "pickItems()"
        }
        packing = state(name = "Packing")
    }
    val shipped = state(name = "Shipped")
    val cancelled = finalState(name = "Cancelled")
    val done = finalState(name = "Done")

    transition(source = start, target = draft)
    transition(source = draft, target = confirmed) {
        trigger = "confirm()"
        guard = "[valid]"
    }
    transition(source = draft, target = cancelled) {
        trigger = "cancel()"
    }
    transition(source = confirmed, target = paymentCheck) {
        trigger = "process()"
    }
    transition(source = paymentCheck, target = processing) { guard = "[payment.ok]" }
    transition(source = paymentCheck, target = cancelled)  { guard = "[else]" }

    // Innere Übergänge werden — wie im compositeState-KDoc beschrieben — auf State-Machine-Ebene deklariert
    transition(source = resume, target = picking)
    transition(source = picking, target = packing) { trigger = "packed" }

    transition(source = processing, target = shipped) {
        trigger = "ship()"
        effect = "notifyCustomer()"
    }
    transition(source = shipped, target = done)
}
