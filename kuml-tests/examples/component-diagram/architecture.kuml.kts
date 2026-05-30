// architecture.kuml.kts — Beispiel für ein UML-Komponentendiagramm in kUML
// Zeigt: Interfaces, Komponenten mit Ports, provides/requires, Connectors,
//        verschachtelte Komponente

componentDiagram("Order System — Architecture") {

    // Interfaces — Verträge zwischen Komponenten
    val orderApi = interfaceOf("IOrderApi") {
        operation("placeOrder", returnType = "Order")
        operation("cancelOrder", returnType = "Boolean")
    }
    val paymentApi = interfaceOf("IPaymentApi") {
        operation("charge", returnType = "Receipt")
    }
    val eventBus = interfaceOf("IEventBus") {
        operation("publish")
    }

    // Komponenten
    val order = component("OrderService") {
        stereotypes += "service"
        port("api", type = typeRef(orderApi))
        port("events")
        provides(orderApi)
        requires(eventBus)

        // Verschachtelt — Persistence ist Teil von OrderService
        component("OrderRepository") {
            stereotypes += "repository"
            port("db")
        }
    }

    val payment = component("PaymentService") {
        stereotypes += "service"
        port("api", type = typeRef(paymentApi))
        provides(paymentApi)
    }

    val invoice = component("InvoiceService") {
        port("orderEvents")
        requires(eventBus)
    }

    val broker = component("MessageBroker") {
        port("pub")
        port("sub")
        provides(eventBus)
    }

    // Connectors zwischen Ports
    connect(end1 = order,   port1 = "events",      end2 = broker,  port2 = "pub")
    connect(end1 = invoice, port1 = "orderEvents", end2 = broker,  port2 = "sub")

    // Dependency (loser als Connector — z.B. Konfiguration / Bibliothek)
    dependency(client = order, supplier = payment)
}
