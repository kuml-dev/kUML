// C4 fixture with a relationship repeated in different letter case and whitespace (KUML-C4-W002).
// Customer -> Main System is declared twice, with labels "uses" and "Uses ".
c4Model(name = "Minimal C4") {
    val customer = person(name = "Customer") {
        description = "End user"
    }
    val system = softwareSystem(name = "Main System") {
        description = "Core product"
    }
    val external = softwareSystem(name = "External Service") {
        description = "Third-party API"
        external = true
    }

    relationship(source = customer, target = system) {
        description = "uses"
        technology = "HTTPS"
    }
    relationship(source = customer, target = system) {
        description = "Uses "
        technology = "HTTPS"
    }
    relationship(source = system, target = external) { technology = "REST / JSON" }

    systemContextDiagram(name = "Minimal Context") {
        include(customer, system, external)
    }
}
