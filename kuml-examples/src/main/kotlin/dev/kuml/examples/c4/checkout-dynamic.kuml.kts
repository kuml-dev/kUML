@file:Suppress("unused")

import dev.kuml.c4.dsl.c4Model

// C4 Dynamic Diagram — Checkout Flow.
//
// Models the runtime interaction between the customer, the web frontend, the
// API server, and the order database. Demonstrates request/response pairing:
// the three `interaction(...)` calls are solid arrows with sequence numbers
// and technology tags, the three `response(...)` calls are dashed arrows.
//
// Sample output: kuml render checkout-dynamic.kuml.kts --format svg

c4Model(name = "Checkout — Dynamic") {
    val customer = person(name = "Customer")
    val web = softwareSystem(name = "WebApp")
    val api = softwareSystem(name = "API Server")
    val db = softwareSystem(name = "OrderDB")

    dynamicDiagram(name = "Checkout Flow", description = "Bestellung abschicken") {
        interaction(description = "Submit order", from = customer, to = web, technology = "HTTPS")
        interaction(description = "POST /orders", from = web, to = api, technology = "HTTPS/JSON")
        interaction(description = "INSERT order", from = api, to = db, technology = "JDBC")
        response(description = "ok", from = db, to = api)
        response(description = "201 Created", from = api, to = web)
        response(description = "Confirmation", from = web, to = customer)
    }
}
