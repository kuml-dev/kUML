@file:Suppress("unused")

import dev.kuml.core.dsl.componentDiagram

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
componentDiagram("Order System — Docker") {

    component("OrderService") {
        stereotypes += "service"
        port("api")
        port("events")
    }

    component("PaymentService") {
        stereotypes += "service"
        port("api")
    }

    component("InvoiceService") {
        port("orderEvents")
    }

    component("MessageBroker") {
        port("pub")
        port("sub")
    }
}

// Run: kuml transform order-system-docker.kuml.kts --transformer uml-to-docker --output build/docker-gen/
