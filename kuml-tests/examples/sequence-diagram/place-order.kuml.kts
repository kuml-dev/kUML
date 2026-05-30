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
    message(source = customer, target = ui,    label = "fillCart()")
    message(source = customer, target = ui,    label = "submitOrder()")
    message(source = ui,       target = api,   label = "POST /orders")
    message(source = api,      target = stock, label = "checkAvailability(items)")
    reply(source = stock,      target = api,   label = "availability")

    alt {
        branch(guard = "[allAvailable]") {
            message(source = api,     target = stock,   label = "reserve(items)")
            reply(source = stock,     target = api,     label = "reservationId")
            message(source = api,     target = payment, label = "charge(total)")

            opt(guard = "[customer.hasDiscount]") {
                message(source = api, target = payment, label = "applyDiscount(code)")
            }

            reply(source = payment,   target = api,     label = "receipt")
            message(source = api,     target = db,      label = "INSERT order")
            reply(source = db,        target = api,     label = "orderId")
            reply(source = api,       target = ui,      label = "201 Created")
            reply(source = ui,        target = customer, label = "confirmation")
        }
        branch(guard = "[outOfStock]") {
            reply(source = api,       target = ui,       label = "409 Conflict")
            reply(source = ui,        target = customer, label = "items unavailable")
        }
    }
}
