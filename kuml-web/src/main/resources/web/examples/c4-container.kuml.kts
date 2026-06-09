// c4-container.kuml.kts — minimal C4 Container Diagram example
c4Model(name = "Minimal Banking System") {
    val customer = person(name = "Customer")

    val bankSystem = softwareSystem(name = "Internet Banking") {
        container(name = "Web App") {
            technology = "Kotlin/Ktor"
        }

        container(name = "API Server") {
            technology = "Kotlin/Spring Boot"
        }
    }

    val emailService = softwareSystem(name = "Email Service") {
        external = true
    }

    relationship(source = customer, target = bankSystem) { technology = "HTTPS" }
    relationship(source = bankSystem, target = emailService) { technology = "SMTP" }

    containerDiagram(name = "Banking — Container View") {
        system = bankSystem
        showExternalSystems = true
    }
}
