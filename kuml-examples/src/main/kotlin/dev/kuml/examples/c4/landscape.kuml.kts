@file:Suppress("unused")

import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.dsl.person
import dev.kuml.c4.dsl.relationship
import dev.kuml.c4.dsl.softwareSystem
import dev.kuml.c4.dsl.systemLandscapeDiagram

c4Model(name = "Enterprise Banking Landscape") {
    // Persons / Roles
    val customer =
        person(name = "Customer") {
            description = "A customer using banking services"
        }

    val admin =
        person(name = "Administrator") {
            description = "System administrator managing banking infrastructure"
        }

    // Software Systems
    val mainBanking =
        softwareSystem(name = "Main Banking System") {
            description = "Handles customer accounts, transfers, and payments"
        }

    val creditCard =
        softwareSystem(name = "Credit Card System") {
            description = "Manages credit card operations and billing"
        }

    val loan =
        softwareSystem(name = "Loan Management System") {
            description = "Manages loan applications and disbursements"
        }

    val emailService =
        softwareSystem(name = "Email Service") {
            description = "Sends transactional and marketing emails"
            external = true
        }

    val smsService =
        softwareSystem(name = "SMS Notification Service") {
            description = "Sends SMS alerts and notifications"
            external = true
        }

    // Relationships
    relationship(source = customer, target = mainBanking) {
        description = "Uses"
    }
    relationship(source = customer, target = creditCard) {
        description = "Manages credit cards"
    }
    relationship(source = customer, target = loan) {
        description = "Applies for loans"
    }

    relationship(source = admin, target = mainBanking) {
        description = "Administers"
    }
    relationship(source = admin, target = creditCard) {
        description = "Administers"
    }

    relationship(source = mainBanking, target = emailService) {
        description = "Sends notifications via"
    }
    relationship(source = mainBanking, target = smsService) {
        description = "Sends alerts via"
    }

    relationship(source = creditCard, target = emailService) {
        description = "Sends billing statements via"
    }

    relationship(source = loan, target = emailService) {
        description = "Sends approval letters via"
    }

    // System Landscape Diagram — shows all systems and persons
    systemLandscapeDiagram(name = "Enterprise Banking Landscape") {
        description =
            "High-level overview of all systems and users in the banking enterprise"
    }

    // Alternative view: exclude external systems
    systemLandscapeDiagram(name = "Internal Systems Only") {
        description = "Shows only internal banking systems"
        exclude(emailService, smsService)
    }

    // Selective view: only customer view
    systemLandscapeDiagram(name = "Customer Services") {
        description = "Services available to customers"
        include(customer, mainBanking, creditCard, loan)
    }
}
