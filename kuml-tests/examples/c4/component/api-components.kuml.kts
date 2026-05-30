@file:Suppress("unused")

import dev.kuml.c4.dsl.*
import dev.kuml.c4.model.*

c4Model("Internet Banking System") {
    val system = softwareSystem("Internet Banking") {
        val webApp = container("Web Application")

        val apiServer = container("API Server") {
            component("Login Component") {
                technology = "Spring Security"
                description = "Handles user authentication"
            }

            component("Account Component") {
                technology = "Spring MVC"
                description = "Manages account operations"
            }

            component("Transaction Component") {
                technology = "Spring Data"
                description = "Handles money transfers"
            }

            component("Reporting Component") {
                technology = "Spring Reports"
                description = "Generates financial reports"
            }
        }

        val database = container("Database")
    }

    relationship(webApp, apiServer)
    relationship(apiServer, database)

    componentDiagram("API Server - Components") {
        this.container = apiServer
        showExternalReferences = true
    }
}
