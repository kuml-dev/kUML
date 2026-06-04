@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.soaml.soamlProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.collaboration
import dev.kuml.uml.dsl.stereotype

/**
 * Order Processing SOA — SoaML Example
 *
 * Illustrates the SoaML profile (OMG SoaML 1.0) applied to a simple
 * order-processing scenario with two Participants, a ServiceContract,
 * and a shared MessageType.
 *
 * Diagram type: Class Diagram (hosts both UmlClass and UmlCollaboration)
 * Profile: SoaML (kuml-profile-soaml)
 */
classDiagram("Order Processing SOA") {
    applyProfile(soamlProfile)

    // ── Participants ──────────────────────────────────────────────────────────────

    val orderService =
        classOf("OrderService") {
            stereotype("Participant")
        }

    val paymentService =
        classOf("PaymentService") {
            stereotype("Participant")
        }

    // ── Message Type ──────────────────────────────────────────────────────────────

    classOf("OrderMessage") {
        stereotype("MessageType")
        attribute(name = "orderId", type = "UUID")
        attribute(name = "totalAmount", type = "BigDecimal")
    }

    // ── Service Contract ──────────────────────────────────────────────────────────

    collaboration("OrderPaymentContract") {
        stereotype("ServiceContract")
        role(name = "provider", type = orderService.name)
        role(name = "consumer", type = paymentService.name)
    }
}
