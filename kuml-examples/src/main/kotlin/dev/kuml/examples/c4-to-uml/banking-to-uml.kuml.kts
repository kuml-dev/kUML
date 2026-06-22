@file:Suppress("unused")

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.c4.C4ToUmlTransformer

/*
 * Internet Banking System — C4→UML transformer example (V2.0.25).
 *
 * Demonstrates the `c4-to-uml` M2M transformer converting a C4 model into a
 * UML class diagram script. The resulting script (`uml-from-c4.kuml.kts`) can
 * be rendered directly with kUML as a class diagram.
 *
 * Run via kUML CLI:
 *   kuml transform banking-to-uml.kuml.kts --transformer c4-to-uml --output build/uml-gen/
 *
 * With persons included:
 *   kuml transform banking-to-uml.kuml.kts \
 *     --transformer c4-to-uml --option includePersons=true --output build/uml-gen/
 *
 * With custom diagram name:
 *   kuml transform banking-to-uml.kuml.kts \
 *     --transformer c4-to-uml --option "diagramName=Banking System Architecture" \
 *     --output build/uml-gen/
 */

// ── Build the Internet Banking C4 model ──────────────────────────────────────

val customer =
    C4Person(
        id = "customer",
        name = "Customer",
        description = "A customer using the Internet Banking System",
        external = false,
    )

val internetBankingSystem =
    C4SoftwareSystem(
        id = "internet-banking-system",
        name = "Internet Banking System",
        description = "Allows a customer to view bank account information and make payments.",
        external = false,
        containers = listOf("web-app", "api-application", "database", "message-queue"),
    )

val emailService =
    C4SoftwareSystem(
        id = "email-service",
        name = "Email Service",
        description = "Sends emails to users",
        external = true,
    )

val webApplication =
    C4Container(
        id = "web-app",
        name = "Web Application",
        description = "Provides the Internet Banking user interface over HTTPS",
        technology = "React / TypeScript",
        system = "internet-banking-system",
    )

val apiApplication =
    C4Container(
        id = "api-application",
        name = "API Application",
        description = "Provides Internet Banking functionality via a RESTful API",
        technology = "Spring Boot / Java",
        system = "internet-banking-system",
    )

val database =
    C4Container(
        id = "database",
        name = "Database",
        description = "Stores user accounts, authentication credentials and access logs",
        technology = "PostgreSQL",
        system = "internet-banking-system",
    )

val messageQueue =
    C4Container(
        id = "message-queue",
        name = "Message Queue",
        description = "Handles asynchronous messaging between services",
        technology = "RabbitMQ",
        system = "internet-banking-system",
    )

val signinController =
    C4Component(
        id = "signin-controller",
        name = "Sign In Controller",
        description = "Allows users to sign in to the Internet Banking system",
        technology = "Spring MVC Rest Controller",
        container = "api-application",
    )

val accountsController =
    C4Component(
        id = "accounts-controller",
        name = "Accounts Controller",
        description = "Provides account information and transaction history",
        technology = "Spring MVC Rest Controller",
        container = "api-application",
    )

val model =
    C4Model(
        id = "internet-banking-model",
        name = "Internet Banking System",
        description = "C4 model for the Internet Banking System",
        elements =
            listOf(
                customer,
                internetBankingSystem,
                emailService,
                webApplication,
                apiApplication,
                database,
                messageQueue,
                signinController,
                accountsController,
            ),
        relationships =
            listOf(
                C4Relationship(
                    id = "r1",
                    source = "web-app",
                    target = "api-application",
                    label = "Makes API calls to",
                    technology = "HTTPS / JSON",
                ),
                C4Relationship(
                    id = "r2",
                    source = "api-application",
                    target = "database",
                    label = "Reads from and writes to",
                    technology = "JDBC",
                ),
                C4Relationship(
                    id = "r3",
                    source = "api-application",
                    target = "message-queue",
                    label = "Publishes messages to",
                    technology = "AMQP",
                ),
                C4Relationship(
                    id = "r4",
                    source = "internet-banking-system",
                    target = "email-service",
                    label = "Sends emails via",
                    technology = "SMTP",
                ),
                C4Relationship(
                    id = "r5",
                    source = "signin-controller",
                    target = "accounts-controller",
                    label = "Delegates to",
                ),
            ),
    )

// ── Run the transformer ────────────────────────────────────────────────────────

val transformer = C4ToUmlTransformer()
val result = transformer.transform(source = model, ctx = TransformContext())

when (result) {
    is TransformResult.Success -> {
        val file = result.output.first()
        println("Generated: ${file.relativePath}")
        println("--- Script preview (first 20 lines) ---")
        file.content
            .lines()
            .take(20)
            .forEach { println(it) }
        println("---")
        println("Trace: ${result.trace.links.size} traceability links")
    }
    is TransformResult.Failure -> {
        println("Transformation failed:")
        result.errors.forEach { println("  ERROR: ${it.message}") }
    }
}
