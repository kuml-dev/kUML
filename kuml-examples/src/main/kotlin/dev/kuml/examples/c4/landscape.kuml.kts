@file:Suppress("unused")

import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.dsl.person
import dev.kuml.c4.dsl.relationship
import dev.kuml.c4.dsl.softwareSystem
import dev.kuml.c4.dsl.systemLandscapeDiagram

c4Model("Enterprise Banking Landscape") {
    // Persons / Roles
    val customer =
        person("Customer") {
            description = "A customer using banking services"
        }

    val admin =
        person("Administrator") {
            description = "System administrator managing banking infrastructure"
        }

    // Software Systems
    val mainBanking =
        softwareSystem("Main Banking System") {
            description = "Handles customer accounts, transfers, and payments"
        }

    val creditCard =
        softwareSystem("Credit Card System") {
            description = "Manages credit card operations and billing"
        }

    val loan =
        softwareSystem("Loan Management System") {
            description = "Manages loan applications and disbursements"
        }

    val emailService =
        softwareSystem("Email Service") {
            description = "Sends transactional and marketing emails"
            external = true
        }

    val smsService =
        softwareSystem("SMS Notification Service") {
            description = "Sends SMS alerts and notifications"
            external = true
        }

    // Relationships
    relationship(customer, mainBanking) {
        description = "Uses"
    }
    relationship(customer, creditCard) {
        description = "Manages credit cards"
    }
    relationship(customer, loan) {
        description = "Applies for loans"
    }

    relationship(admin, mainBanking) {
        description = "Administers"
    }
    relationship(admin, creditCard) {
        description = "Administers"
    }

    relationship(mainBanking, emailService) {
        description = "Sends notifications via"
    }
    relationship(mainBanking, smsService) {
        description = "Sends alerts via"
    }

    relationship(creditCard, emailService) {
        description = "Sends billing statements via"
    }

    relationship(loan, emailService) {
        description = "Sends approval letters via"
    }

    // System Landscape Diagram — shows all systems and persons
    systemLandscapeDiagram("Enterprise Banking Landscape") {
        description =
            "High-level overview of all systems and users in the banking enterprise"
    }

    // Alternative view: exclude external systems
    systemLandscapeDiagram("Internal Systems Only") {
        description = "Shows only internal banking systems"
        exclude(emailService, smsService)
    }

    // Selective view: only customer view
    systemLandscapeDiagram("Customer Services") {
        description = "Services available to customers"
        include(customer, mainBanking, creditCard, loan)
    }
}
