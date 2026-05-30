// place-order.kuml.kts — Beispiel für ein UML-Sequenzdiagramm in kUML
// Zeigt: Actor + Component-Lifelines, Sync/Async/Reply Messages,
//        verschachtelte ALT/OPT Fragments

sequenceDiagram("Place Order") {

    // Teilnehmer
    val customer = lifeline("Customer") { isActor = true }
    val ui       = lifeline("Frontend") { represents = typeRef("OrderUI") }
    val api      = lifeline("OrderAPI") { represents = typeRef("OrderService") }
    val stock    = lifeline("StockService")
    val payment  = lifeline("PaymentService")
    val db       = lifeline("OrderDatabase")

    // Hauptablauf
    message(customer, ui,    "fillCart()")
    message(customer, ui,    "submitOrder()")
    message(ui,       api,   "POST /orders")
    message(api,      stock, "checkAvailability(items)")
    reply(stock,      api,   "availability")

    alt {
        branch(guard = "[allAvailable]") {
            message(api,     stock,   "reserve(items)")
            reply(stock,     api,     "reservationId")
            message(api,     payment, "charge(total)")

            opt(guard = "[customer.hasDiscount]") {
                message(api, payment, "applyDiscount(code)")
            }

            reply(payment,   api,     "receipt")
            message(api,     db,      "INSERT order")
            reply(db,        api,     "orderId")
            reply(api,       ui,      "201 Created")
            reply(ui,        customer, "confirmation")
        }
        branch(guard = "[outOfStock]") {
            reply(api,       ui,       "409 Conflict")
            reply(ui,        customer, "items unavailable")
        }
    }
}
