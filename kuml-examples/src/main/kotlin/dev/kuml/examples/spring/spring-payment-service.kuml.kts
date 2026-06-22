@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.javaee.javaEeProfile
import dev.kuml.profile.spring.springProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype

/**
 * Payment Service — Spring + JavaEE Profile Example (V1.1)
 *
 * Illustrates both profiles applied simultaneously (D12/D13):
 * - PaymentProcessor carries JavaEE «Service» + Spring «RestController» (comma-joined header)
 * - PaymentRepository carries Spring «SpringData» (specializes JavaEE Repository)
 *
 * Profiles: JavaEE (kuml-profile-javaee) + Spring (kuml-profile-spring)
 */
classDiagram(name = "Payment Service") {
    applyProfile(javaEeProfile)
    applyProfile(springProfile) // both profiles simultaneously

    // ── Service + REST Layer ──────────────────────────────────────────────────────

    classOf(name = "PaymentProcessor") {
        stereotype(name = "Service") // from JavaEE — transactional service
        // from Spring — REST endpoint
        stereotype(name = "RestController") {
            "produces" to "application/json"
        }
        operation(name = "process") { returns(typeName = "PaymentResult") }
    }

    // ── Data Layer ────────────────────────────────────────────────────────────────

    classOf(name = "PaymentRepository") {
        stereotype(name = "SpringData") { "readOnly" to false } // specializes JavaEE Repository
        operation(name = "findByOrderId") { returns(typeName = "Payment") }
    }
}
