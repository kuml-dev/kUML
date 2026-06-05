@file:Suppress("unused")

import dev.kuml.c4.dsl.*
import dev.kuml.c4.model.*

c4Model("Internet Banking System") {
    val customer = person("Customer")

    val mainSystem = softwareSystem("Internet Banking System") {
        val webApp = container("Web Application")
        val apiServer = container("API Server")
        val database = container("Database")
    }

    val emailSystem = softwareSystem("Email Service") {
        external = true
    }

    relationship(webApp, apiServer)
    relationship(apiServer, database)
    relationship(apiServer, emailSystem)

    dynamicDiagram("Place Order") {
        interaction("Customer places order", from = customer, to = webApp, technology = "Browser")
        interaction("Web app calls API", from = webApp, to = apiServer, technology = "REST/JSON")
        interaction("Validate order", from = apiServer, to = database, technology = "SQL")
        response("Order valid", from = database, to = apiServer)
        interaction("Send confirmation", from = apiServer, to = emailSystem, technology = "SMTP")
        response("Email sent", from = emailSystem, to = apiServer)
        response("Order created", from = apiServer, to = webApp)
        response("Confirmation shown", from = webApp, to = customer, technology = "HTML")

        title("Place Order - Dynamic View")
    }
}
