classDiagram(name = "A") {
    val person = classOf(name = "Person") { attribute(name = "name", type = "String") }
    val customer = classOf(name = "Customer") { attribute(name = "no", type = "String") }
    generalization(specific = customer, general = person)
}
