classDiagram(name = "Order Domain") {
    val status = enumOf(name = "OrderStatus") {
        literal(name = "DRAFT")
        literal(name = "CONFIRMED")
        literal(name = "SHIPPED")
        literal(name = "CANCELLED")
    }

    val payable = interfaceOf(name = "Payable") {
        operation(name = "pay") { returns(typeName = "Boolean") }
    }

    // Abstrakte Basisklasse — Name wird kursiv gerendert
    val abstractEntity = classOf(name = "AbstractEntity") {
        isAbstract = true
        attribute(name = "id", type = "UUID", visibility = Visibility.PROTECTED, isReadOnly = true)
    }

    val customer = classOf(name = "Customer") {
        attribute(name = "id",    type = "UUID")
        attribute(name = "name",  type = "String")
        attribute(name = "email", type = "String")
    }

    val order = classOf(name = "Order") {
        attribute(name = "id",     type = "UUID")
        attribute(name = "status", type = status, defaultValue = "DRAFT")
        attribute(name = "total",  type = "BigDecimal", visibility = Visibility.PRIVATE)
        attribute(name = "taxRate", type = "BigDecimal", isStatic = true, defaultValue = "0.19")
        implements(iface = payable)
        extends(general = abstractEntity)

        operation(name = "place") {
            visibility = Visibility.PUBLIC
            parameter(name = "items", type = "List<OrderItem>")
            returns(typeName = "OrderId")
        }
        operation(name = "confirm") { returns(typeName = "Boolean") }

        // OCL-Invariante — wird per `kuml validate` geprüft
        constraint(name = "PositiveTotal", body = "self.total >= 0")
    }

    val orderItem = classOf(name = "OrderItem") {
        attribute(name = "quantity",  type = "Int")
        attribute(name = "unitPrice", type = "BigDecimal")
    }

    val subscription = classOf(name = "Subscription") {
        attribute(name = "renewalDate", type = "LocalDate")
        attribute(name = "interval",    type = "Period")
    }

    // Generalisierung: Subscription erbt von Order (top-level Schreibweise)
    generalization(specific = subscription, general = order)

    // Abhängigkeit: Order nutzt eine Notification-Klasse, ohne sie zu besitzen
    val notification = classOf(name = "NotificationService")
    dependency(client = order, supplier = notification, name = "notifies")

    // Assoziation: Kunde besitzt 0..n Bestellungen; Order kennt seinen Customer nicht (nicht navigierbar)
    association(source = customer, target = order) {
        source { multiplicity(spec = "1"); navigable = false }
        target { multiplicity(spec = "0..*"); role = "orders" }
    }

    // Komposition: eine Bestellung besteht aus 1..n OrderItems
    association(source = order, target = orderItem) {
        aggregation = AggregationKind.COMPOSITE
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "1..*"); role = "items" }
    }

    // UML-Notiz (Comment): Freitext-Kasten mit gefalteter Ecke, per gestrichelter
    // Linie an ein Element angehängt — hier an Order.
    comment(
        text = "Encapsulates the full order lifecycle from placement to fulfillment.",
        firstAnchor = order,
    )
}
