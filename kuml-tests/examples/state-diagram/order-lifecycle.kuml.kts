// order-lifecycle.kuml.kts — Beispiel für ein UML-Zustandsdiagramm in kUML
// Zeigt: Initial, einfache States, Composite State mit Substates,
//        Choice, Final State, Transitions mit Trigger/Guard/Effect

stateDiagram(name = "Order Lifecycle") {

    val init = initialState()
    val draft = state(name = "Draft") {
        entry = "validate()"
    }
    val confirmed = state(name = "Confirmed") {
        entry = "reserveStock()"
    }

    val processing = compositeState(name = "Processing") {
        state(name = "Picking")
        state(name = "Packing")
        state(name = "Shipping")
    }

    val paymentCheck = choice(name = "PaymentOK?")
    val cancelled = finalState(name = "Cancelled")
    val completed = finalState(name = "Completed")

    // Hauptpfad
    transition(source = init, target = draft)
    transition(source = draft, target = confirmed) { trigger = "confirm()"; guard = "[isValid]" }
    transition(source = confirmed, target = paymentCheck) { trigger = "submitPayment()" }
    transition(source = paymentCheck, target = processing) { guard = "[paid]" }
    transition(source = paymentCheck, target = cancelled) { guard = "[failed]"; effect = "refund()" }
    transition(source = processing, target = completed) { trigger = "delivered()" }

    // Innere Transitionen des Composite — über das zurückgegebene Handle adressiert
    val picking = processing.substates[0]
    val packing = processing.substates[1]
    val shipping = processing.substates[2]
    transition(source = picking, target = packing) { trigger = "picked()" }
    transition(source = packing, target = shipping) { trigger = "packed()" }

    // Abbruch jederzeit möglich
    transition(source = draft, target = cancelled) { trigger = "cancel()" }
    transition(source = confirmed, target = cancelled) { trigger = "cancel()" }
}
