// order-domain.kuml.kts — Beispiel für ein UML-Klassendiagramm in kUML
// Zeigt: Klassen, Interfaces, Enums, Assoziation (Komposition), Vererbung

classDiagram("Order Domain") {
    showOperations = false

    val status = enumOf("OrderStatus") {
        literal("DRAFT")
        literal("CONFIRMED")
        literal("SHIPPED")
        literal("CANCELLED")
    }

    val payable = interfaceOf("Payable") {
        operation("pay", returnType = "Boolean")
    }

    val customer = classOf("Customer") {
        attribute("id", type = "UUID")
        attribute("email", type = "String")
        attribute("name", type = "String")
    }

    val order = classOf("Order") {
        attribute("id", type = "UUID")
        attribute("status", type = status)
        implements(payable)
    }

    val orderItem = classOf("OrderItem") {
        attribute("quantity", type = "Int")
        attribute("unitPrice", type = "BigDecimal")
    }

    val subscription = classOf("Subscription") {
        attribute("renewalDate", type = "LocalDate")
        extends(order)
    }

    association(source = customer, target = order) {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "orders" }
    }

    association(source = order, target = orderItem) {
        aggregation = AggregationKind.COMPOSITE
        source { multiplicity("1") }
        target { multiplicity("1..*"); role = "items" }
    }
}
