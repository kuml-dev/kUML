// order-snapshot.kuml.kts — UML 2.x object diagram example (V1.1)
//
// A snapshot illustrating a specific configuration that the class diagram in
// `class-diagram/order-domain.kuml.kts` is intended to permit: one customer
// holding an order with two items.
//
// The classifiers we instantiate are constructed directly as UmlClass values
// at script scope — `classOf` is scoped to a UmlModelScope (inside a class
// diagram), which we don't open here.

val customer = UmlClass(
    id = "Customer",
    name = "Customer",
    attributes = listOf(
        UmlProperty(id = "Customer::id", name = "id", type = UmlTypeRef("UUID")),
        UmlProperty(id = "Customer::name", name = "name", type = UmlTypeRef("String")),
        UmlProperty(id = "Customer::email", name = "email", type = UmlTypeRef("String")),
    ),
)

val order = UmlClass(
    id = "Order",
    name = "Order",
    attributes = listOf(
        UmlProperty(id = "Order::id", name = "id", type = UmlTypeRef("UUID")),
        UmlProperty(id = "Order::status", name = "status", type = UmlTypeRef("String")),
        UmlProperty(id = "Order::total", name = "total", type = UmlTypeRef("BigDecimal")),
    ),
)

val orderItem = UmlClass(
    id = "OrderItem",
    name = "OrderItem",
    attributes = listOf(
        UmlProperty(id = "OrderItem::sku", name = "sku", type = UmlTypeRef("String")),
        UmlProperty(id = "OrderItem::quantity", name = "quantity", type = UmlTypeRef("Int")),
        UmlProperty(id = "OrderItem::unitPrice", name = "unitPrice", type = UmlTypeRef("BigDecimal")),
    ),
)

objectDiagram(name = "Order #42 — Alice's checkout") {
    val alice = instanceOf(classifier = customer, name = "alice") {
        slot(feature = "id", value = literal("c0ffee42"))
        slot(feature = "name", value = literal("\"Alice\""))
        slot(feature = "email", value = literal("\"alice@example.com\""))
    }

    val order42 = instanceOf(classifier = order, name = "order42") {
        slot(feature = "id", value = literal("ord-42"))
        slot(feature = "status", value = literal("\"CONFIRMED\""))
        slot(feature = "total", value = literal("47.50"))
    }

    val item1 = instanceOf(classifier = orderItem, name = "item1") {
        slot(feature = "sku", value = literal("\"KUML-MUG\""))
        slot(feature = "quantity", value = literal("2"))
        slot(feature = "unitPrice", value = literal("19.95"))
    }

    val item2 = instanceOf(classifier = orderItem, name = "item2") {
        slot(feature = "sku", value = literal("\"KUML-STICKER\""))
        slot(feature = "quantity", value = literal("1"))
        slot(feature = "unitPrice", value = literal("7.60"))
    }

    link(from = alice, to = order42, sourceRole = "buyer", targetRole = "orders")
    link(from = order42, to = item1, targetRole = "items")
    link(from = order42, to = item2, targetRole = "items")
}
