// uml-class.kuml.kts — minimal UML Class Diagram example
classDiagram(name = "Order Domain") {
    val customer = classOf(name = "Customer") {
        attribute(name = "name", type = "String")
        attribute(name = "email", type = "String")
    }

    val order = classOf(name = "Order") {
        attribute(name = "id", type = "Long")
        attribute(name = "status", type = "String")
    }

    association(source = customer, target = order) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "orders" }
    }
}
