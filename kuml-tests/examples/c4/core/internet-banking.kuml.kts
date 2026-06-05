@file:Suppress("unused")

import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.model.C4Model

/**
 * Internet Banking System - C4 Model Example
 *
 * A comprehensive example demonstrating all C4 metamodel elements:
 * - Persons (users and administrators)
 * - Software Systems (main system and external systems)
 * - Containers (web app, API, database, message queue)
 * - Components (authentication, payment, reporting services)
 * - Relationships (various communication patterns)
 * - Deployment Nodes (cloud infrastructure and databases)
 */
val internetBankingModel: C4Model = c4Model("Internet Banking System") {
    // ── Persons ──────────────────────────────────────────────────────────────

    val customer = person("Personal Banking Customer") {
        description = "A customer using the internet banking system to view account balances and transfer money"
        location = "User's Office or Home"
    }

    val systemAdmin = person("System Administrator") {
        description = "An administrator responsible for the operation of the internet banking system"
        external = true
        location = "Bank's Operations Center"
    }

    // ── Software Systems ──────────────────────────────────────────────────────

    val internetBankingSystem = softwareSystem("Internet Banking System") {
        description = "Allows customers to view information about their bank accounts and make payments"
        location = "Bank's Cloud Environment"

        // Containers within Internet Banking System
        container("Web Application") {
            technology = "React / JavaScript"
            description = "Provides banking functionality to customers via their web browser"

            component("Login Controller") {
                technology = "Spring MVC"
                description = "Allows users to login"
            }

            component("Account Summary Page") {
                technology = "JSP"
                description = "Displays account summaries"
            }

            component("Resetpassword Page") {
                technology = "JSP"
                description = "Allows users to change their password"
            }
        }

        container("API Application") {
            technology = "Spring Boot / Java"
            description = "Provides internet banking functionality via a JSON/HTTPS API"

            component("Sign In Controller") {
                technology = "Spring MVC Rest Controller"
                description = "Allows users to authenticate"
            }

            component("Accounts Summary Controller") {
                technology = "Spring MVC Rest Controller"
                description = "Provides account balance information"
            }

            component("Money Transfer Controller") {
                technology = "Spring MVC Rest Controller"
                description = "Handles money transfer requests"
            }

            component("Security Component") {
                technology = "Spring Security"
                description = "Provides functionality related to signing in, changing passwords, etc."
            }

            component("Money Transfer Service") {
                technology = "Spring Service"
                description = "Provides money transfer business logic"
            }

            component("Account Repository") {
                technology = "Spring Data"
                description = "Provides a simple repository interface for Account instances"
            }
        }

        container("Database") {
            technology = "Relational Database Schema"
            description = "Stores user account information, hashed authentication credentials, access logs, etc."
        }

        container("Message Bus") {
            technology = "RabbitMQ"
            description = "Asynchronous event bus for system to system communication"
        }
    }

    val mainframeSystem = softwareSystem("Mainframe Banking System") {
        description = "Stores all of the core banking information about customers, accounts, transactions, etc."
        external = true
        location = "Bank's Data Center"
    }

    val emailSystem = softwareSystem("E-mail System") {
        description = "The internal Microsoft Exchange e-mail system"
        external = true
        location = "Bank's Data Center"
    }

    val atm = softwareSystem("ATM") {
        description = "Allows customers to withdraw cash"
        external = true
        location = "Retail Banks"
    }

    // ── Relationships ────────────────────────────────────────────────────────

    relationship(customer, internetBankingSystem) {
        description = "Uses the internet banking system to manage accounts"
        technology = "HTTPS"
    }

    relationship(internetBankingSystem, mainframeSystem) {
        description = "Reads from and writes to the mainframe system"
        technology = "Synchronous API calls (JSON/HTTPS)"
    }

    relationship(internetBankingSystem, emailSystem) {
        description = "Sends email messages to"
        technology = "SMTP"
    }

    relationship(customer, atm) {
        description = "Withdraws cash from"
        technology = "ATM API"
    }

    relationship(atm, mainframeSystem) {
        description = "Uses the mainframe system to access bank account information"
        technology = "Synchronous API calls (XML/HTTPS)"
    }

    relationship(systemAdmin, internetBankingSystem) {
        description = "Administers"
        technology = "SSH"
    }

    // ── Deployment Nodes ─────────────────────────────────────────────────────

    deploymentNode("Customer's Computer") {
        technology = "Microsoft Windows or Apple macOS"
        instances = 1

        node("Web Browser") {
            technology = "Chrome, Firefox, Safari, or Edge"
        }
    }

    deploymentNode("Bank's Web Server") {
        technology = "Ubuntu 16.04 LTS"
        instances = 4

        node("Tomcat") {
            technology = "Tomcat 8.x"
            instances = 1
        }

        node("Spring MVC") {
            technology = "Spring Framework"
            instances = 4
        }
    }

    deploymentNode("Bank's Database Server") {
        technology = "Ubuntu 16.04 LTS"
        instances = 1

        node("Database") {
            technology = "Oracle Database"
            instances = 1
        }
    }

    deploymentNode("Bank's Message Bus") {
        technology = "Ubuntu 16.04 LTS"
        instances = 1

        node("RabbitMQ") {
            technology = "RabbitMQ"
            instances = 1
        }
    }

    deploymentNode("Mainframe") {
        technology = "IBM Mainframe"
        instances = 1
    }
}
