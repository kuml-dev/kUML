// place-order.kuml.kts — Beispiel für ein UML-Sequenzdiagramm in kUML
// Zeigt: Actor + Component-Lifelines, Sync/Async/Reply Messages,
//        verschachtelte ALT/OPT Fragments

sequenceDiagram(name = "Place Order") {

    // Teilnehmer
    val customer = lifeline(name = "Customer") { isActor = true }
    val ui       = lifeline(name = "Frontend") { represents = typeRef(name = "OrderUI") }
    val api      = lifeline(name = "OrderAPI") { represents = typeRef(name = "OrderService") }
    val stock    = lifeline(name = "StockService")
    val payment  = lifeline(name = "PaymentService")
    val db       = lifeline(name = "OrderDatabase")

    // Hauptablauf
    message(from = customer, to = ui,    label = "fillCart()")
    message(from = customer, to = ui,    label = "submitOrder()")
    message(from = ui,       to = api,   label = "POST /orders")
    message(from = api,      to = stock, label = "checkAvailability(items)")
    reply(from = stock,      to = api,   label = "availability")

    alt {
        branch(guard = "[allAvailable]") {
            message(from = api, to = stock,   label = "reserve(items)")
            reply(from = stock, to = api,     label = "reservationId")
            message(from = api, to = payment, label = "charge(total)")

            opt(guard = "[customer.hasDiscount]") {
                message(from = api, to = payment, label = "applyDiscount(code)")
            }

            reply(from = payment, to = api,     label = "receipt")
            message(from = api, to = db,      label = "INSERT order")
            reply(from = db, to = api,     label = "orderId")
            reply(from = api, to = ui,      label = "201 Created")
            reply(from = ui, to = customer, label = "confirmation")
        }
        branch(guard = "[outOfStock]") {
            reply(from = api, to = ui,       label = "409 Conflict")
            reply(from = ui, to = customer, label = "items unavailable")
        }
    }
}
