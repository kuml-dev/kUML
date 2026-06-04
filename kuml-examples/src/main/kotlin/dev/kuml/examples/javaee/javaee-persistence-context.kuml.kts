@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.javaee.javaEeProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype

/**
 * JavaEE PersistenceContext — Feature-Level Stereotype Example (V1.1.2)
 *
 * Illustrates the JavaEE profile «PersistenceContext» stereotype applied at
 * Property-level (field injection): the `em: EntityManager` attribute on
 * OrderRepository carries the stereotype, making the JPA injection point
 * explicit in the UML model.
 *
 * Profile: JavaEE (kuml-profile-javaee)
 */
classDiagram("Order Repository — JPA Injection") {
    applyProfile(javaEeProfile)

    // ── Persistence Layer ─────────────────────────────────────────────────────────

    classOf("Order") {
        stereotype("Entity") {
            "tableName" to "orders"
            "schema" to "shop"
            "cacheable" to false
        }
        attribute(name = "id", type = "UUID")
        attribute(name = "amount", type = "BigDecimal")
        attribute(name = "status", type = "String")
    }

    classOf("OrderRepository") {
        stereotype("Repository") { "dataSource" to "shopDb" }

        // ── Feature-Level: «PersistenceContext» on property (V1.1.2) ─────────────
        attribute("em", "EntityManager") {
            stereotype("PersistenceContext") {
                "unitName" to "shopPU"
                "type" to "TRANSACTION"
            }
        }

        operation(name = "findById") { returns("Order") }
        operation(name = "save") { returns("Order") }
        operation(name = "findByStatus") { returns("List<Order>") }
    }
}
