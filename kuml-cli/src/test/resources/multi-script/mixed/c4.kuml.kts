// Minimal C4 fixture for RenderPipelineTest.
// Two persons / systems and one relationship — enough for ELK to lay out.
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
    relationship(source = system, target = external) { technology = "REST / JSON" }

    systemContextDiagram(name = "Minimal Context") {
        include(customer, system, external)
    }
}
