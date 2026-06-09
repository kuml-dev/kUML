// order-domain.kuml.kts — Beispiel für ein UML-Klassendiagramm in kUML
// Zeigt: Klassen, Interfaces, Enums, Assoziation (Komposition), Vererbung

classDiagram(name = "Order Domain") {
    showOperations = false

    val status = enumOf(name = "OrderStatus") {
        literal(name = "DRAFT")
        literal(name = "CONFIRMED")
        literal(name = "SHIPPED")
        literal(name = "CANCELLED")
    }

    val payable = interfaceOf(name = "Payable") {
        operation(name = "pay(): Boolean")
    }

    val customer = classOf(name = "Customer") {
        attribute(name = "id", type = "UUID")
        attribute(name = "email", type = "String")
        attribute(name = "name", type = "String")
    }

    val order = classOf(name = "Order") {
        attribute(name = "id", type = "UUID")
        attribute(name = "status", type = status)
        implements(iface = payable)
    }

    val orderItem = classOf(name = "OrderItem") {
        attribute(name = "quantity", type = "Int")
        attribute(name = "unitPrice", type = "BigDecimal")
    }

    val subscription = classOf(name = "Subscription") {
        attribute(name = "renewalDate", type = "LocalDate")
        extends(general = order)
    }

    association(source = customer, target = order) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "orders" }
    }

    association(source = order, target = orderItem) {
        aggregation = AggregationKind.COMPOSITE
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "1..*"); role = "items" }
    }
}
