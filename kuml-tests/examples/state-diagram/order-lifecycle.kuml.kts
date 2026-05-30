// order-lifecycle.kuml.kts — Beispiel für ein UML-Zustandsdiagramm in kUML
// Zeigt: Initial, einfache States, Composite State mit Substates,
//        Choice, Final State, Transitions mit Trigger/Guard/Effect

stateDiagram("Order Lifecycle") {

    val init = initialState()
    val draft = state("Draft") {
        entry = "validate()"
    }
    val confirmed = state("Confirmed") {
        entry = "reserveStock()"
    }

    val processing = compositeState("Processing") {
        state("Picking")
        state("Packing")
        state("Shipping")
    }

    val paymentCheck = choice("PaymentOK?")
    val cancelled = finalState("Cancelled")
    val completed = finalState("Completed")

    // Hauptpfad
    transition(init, draft)
    transition(draft, confirmed) { trigger = "confirm()"; guard = "[isValid]" }
    transition(confirmed, paymentCheck) { trigger = "submitPayment()" }
    transition(paymentCheck, processing) { guard = "[paid]" }
    transition(paymentCheck, cancelled) { guard = "[failed]"; effect = "refund()" }
    transition(processing, completed) { trigger = "delivered()" }

    // Innere Transitionen des Composite — über das zurückgegebene Handle adressiert
    val picking = processing.substates[0]
    val packing = processing.substates[1]
    val shipping = processing.substates[2]
    transition(picking, packing) { trigger = "picked()" }
    transition(packing, shipping) { trigger = "packed()" }

    // Abbruch jederzeit möglich
    transition(draft, cancelled) { trigger = "cancel()" }
    transition(confirmed, cancelled) { trigger = "cancel()" }
}
