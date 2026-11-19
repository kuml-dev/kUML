// C4 fixture with a repeated, identical relationship (KUML-C4-W001).
// Customer -> Main System is declared twice; the DSL keeps both with distinct ids.
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

    relationship(source = customer, target = system) { technology = "HTTPS" }
    relationship(source = customer, target = system) { technology = "HTTPS" }
    relationship(source = system, target = external) { technology = "REST / JSON" }

    systemContextDiagram(name = "Minimal Context") {
        include(customer, system, external)
    }
}
