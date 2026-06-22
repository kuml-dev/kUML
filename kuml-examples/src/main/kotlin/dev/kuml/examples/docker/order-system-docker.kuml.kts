@file:Suppress("unused")

import dev.kuml.core.dsl.componentDiagram
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.port

/**
 * Order System Docker — UML component diagram for Dockerfile generation (V2.0.24).
 *
 * Uses the same Order System architecture model (OrderService, PaymentService,
 * InvoiceService, MessageBroker) and targets the `uml-to-docker` M2M transformer
 * to produce a Dockerfile per component.
 *
 * Run:
 * ```
 * kuml transform order-system-docker.kuml.kts --transformer uml-to-docker --output build/docker-gen/
 * ```
 *
 * Produces four Dockerfiles:
 *   OrderService/Dockerfile
 *   PaymentService/Dockerfile
 *   InvoiceService/Dockerfile
 *   MessageBroker/Dockerfile
 *
 * Each Dockerfile is a JVM-ready single-stage image using eclipse-temurin:21-jre-alpine.
 *
 * With custom base image + port:
 * ```
 * kuml transform order-system-docker.kuml.kts \
 *   --transformer uml-to-docker \
 *   --option baseImage=amazoncorretto:17-alpine \
 *   --option port=9090 \
 *   --output build/docker-gen/
 * ```
 */
componentDiagram(name = "Order System — Docker") {

    component(name = "OrderService") {
        stereotypes += "service"
        port(name = "api")
        port(name = "events")
    }

    component(name = "PaymentService") {
        stereotypes += "service"
        port(name = "api")
    }

    component(name = "InvoiceService") {
        port(name = "orderEvents")
    }

    component(name = "MessageBroker") {
        port(name = "pub")
        port(name = "sub")
    }
}

// Run: kuml transform order-system-docker.kuml.kts --transformer uml-to-docker --output build/docker-gen/
