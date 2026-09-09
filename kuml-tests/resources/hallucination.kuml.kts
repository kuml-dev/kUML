classDiagram(name = "Order") {
    val customer = classOf(name = "Customer") {
        attribute(name = "customerId", type = "String")
    }
    val order = classOf(name = "Order") {
        attribute(name = "date", type = "LocalDate")
        operation(name = "cancel") { returns(typeName = "Boolean") }
    }
    association(source = customer, target = order) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*") }
    }

    aggregation(source = customer, target = order)    // Hallucination: does not exist here
}