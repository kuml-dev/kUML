@file:Suppress("unused")

import dev.kuml.c4.dsl.*
import dev.kuml.c4.model.*

c4Model("Internet Banking System") {
    val customer = person("Customer") {
        description = "A bank customer"
        external = false
    }

    val admin = person("System Administrator") {
        description = "A system administrator"
        external = true
    }

    val mainSystem = softwareSystem("Internet Banking System") {
        description = "Allows customers to view information and make payments"
        external = false
    }

    val emailSystem = softwareSystem("Email Service") {
        description = "External email provider"
        external = true
    }

    val mainframeSystem = softwareSystem("Mainframe Banking System") {
        description = "Stores all core banking information"
        external = true
    }

    // Relationships
    relationship(customer, mainSystem) {
        technology = "HTTPS / Browser"
    }

    relationship(admin, mainSystem) {
        technology = "HTTP"
    }

    relationship(mainSystem, emailSystem) {
        technology = "SMTP"
    }

    relationship(mainSystem, mainframeSystem) {
        technology = "JSON/HTTPS"
    }

    // Diagram
    systemContextDiagram("Internet Banking - System Context") {
        include(customer, admin, mainSystem, emailSystem, mainframeSystem)
        title("Internet Banking System Context")
    }
}
