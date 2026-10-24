classDiagram(name = "B") {
    val person = classOf(name = "Person") { attribute(name = "name", type = "String") }
    val customer = classOf(name = "Customer") { attribute(name = "no", type = "String") }
    generalization(specific = customer, general = person)
    val order = classOf(name = "Order") { attribute(name = "total", type = "Int") }
    association(source = order, target = customer, id = "assoc-order-customer")
}
