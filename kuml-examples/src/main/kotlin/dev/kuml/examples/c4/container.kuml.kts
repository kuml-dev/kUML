@file:Suppress("unused")

import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.dsl.containerDiagram
import dev.kuml.c4.dsl.person
import dev.kuml.c4.dsl.relationship
import dev.kuml.c4.dsl.softwareSystem

c4Model("Internet Banking System") {
    val customer =
        person("Customer") {
            description = "A customer using the Internet Banking System"
        }

    val mainSystem =
        softwareSystem("Internet Banking System") {
            description =
                "Allows a customer to view information about their bank accounts, and make payments."

            container("Web Application") {
                description = "Provides user interface over HTTPS"
                technology = "Kotlin/Ktor"
            }

            container("API Server") {
                description = "Provides core banking functionality"
                technology = "Kotlin/Spring Boot"
            }

            container("Database") {
                description = "Stores user, account and transaction information"
                technology = "PostgreSQL"
            }

            container("Message Queue") {
                description = "Handles asynchronous tasks"
                technology = "RabbitMQ"
            }
        }

    val emailSystem =
        softwareSystem("Email Service") {
            description = "Sends emails to users"
            external = true
        }

    val notificationSystem =
        softwareSystem("Notification Service") {
            description = "Sends push notifications"
            external = true
        }

    // Relationships
    relationship(customer, mainSystem) { description = "Uses" }
    relationship(mainSystem, emailSystem) { description = "Sends emails via" }
    relationship(mainSystem, notificationSystem) { description = "Sends notifications via" }

    // Container diagram showing the internal structure of the Internet Banking System
    containerDiagram("Internet Banking - Containers") {
        system = mainSystem
        showExternalSystems = true
        description =
            "Shows the container structure of the Internet Banking System and its dependencies"
    }

    // Alternative view without external systems
    containerDiagram("Internet Banking - Containers (Internal Only)") {
        system = mainSystem
        showExternalSystems = false
        description = "Shows only the internal containers of the Internet Banking System"
    }
}
