classDiagram(name = "B") {
    val address = classOf(name = "Address") {
        attribute(name = "street", type = "String")
    }
    val customer = classOf(name = "Customer") {
        attribute(name = "name", type = "String")
    }
    association(source = customer, target = address, id = "assoc-customer-address")
}
