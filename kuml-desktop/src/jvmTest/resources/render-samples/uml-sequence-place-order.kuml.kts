// uml-sequence-place-order.kuml.kts — auto-synced from vault example
// Source of truth: 03 Bereiche/kUML/Beispiele/19 UML Sequence – API Submit.md
// Do not edit by hand; update the vault note and re-run the sync.

sequenceDiagram(name = "Place Order — API Submit") {
    val customer = lifeline(name = "Customer") { isActor = true }
    val frontend = lifeline(name = "Frontend")
    val backend  = lifeline(name = "Backend")
    val db       = lifeline(name = "OrderDB")

    message(from = customer, to = frontend, label = "submitOrder()")
    message(from = frontend, to = backend,  label = "POST /orders")

    alt {
        branch(guard = "[valid]") {
            message(from = backend, to = db, label = "INSERT order")
            reply(from = db, to = backend, label = "ok")
            reply(from = backend, to = frontend, label = "201 Created")
        }
        branch(guard = "[invalid]") {
            reply(from = backend, to = frontend, label = "400 Bad Request")
        }
    }

    reply(from = frontend, to = customer, label = "confirmation")
}
