sequenceDiagram(name = "Place Order — API Submit") {
    val customer = lifeline(name = "Customer") { isActor = true }
    val frontend = lifeline(name = "Frontend")
    val backend  = lifeline(name = "Backend")
    val db       = lifeline(name = "OrderDB")
    val logger   = lifeline(name = "AuditLog")

    message(from = customer, to = frontend, label = "submitOrder()")
    message(from = frontend, to = backend,  label = "POST /orders")

    // Feuer-und-vergessen: Backend loggt asynchron, ohne auf Antwort zu warten
    asyncMessage(from = backend, to = logger, label = "log('order received')")

    // Retry-Handler-Lifeline existiert erst ab ihrer create()-Message — muss vorab deklariert sein,
    // damit create()/delete() innerhalb des alt-Branches darauf zeigen können.
    val retryHandler = lifeline(name = "RetryHandler")

    alt {
        branch(guard = "[valid]") {
            // Retry-Handler wird für diese Transaktion dynamisch erzeugt …
            create(from = backend, to = retryHandler)

            loop(guard = "[attempts < 3]") {
                message(from = backend, to = db, label = "INSERT order")
            }
            reply(from = db, to = backend, label = "ok")

            opt(guard = "[auditRequired]") {
                asyncMessage(from = backend, to = logger, label = "log('order persisted')")
            }

            // … und am Ende der Transaktion wieder zerstört
            delete(from = backend, to = retryHandler)

            reply(from = backend, to = frontend, label = "201 Created")
        }
        branch(guard = "[invalid]") {
            reply(from = backend, to = frontend, label = "400 Bad Request")
        }
    }

    reply(from = frontend, to = customer, label = "confirmation")
}
