// kuml-getting-started.kuml.kts
// Two tiny diagrams to walk a new user through kUML in five minutes.
// Render with:
//   kuml render docs/examples/kuml-getting-started.kuml.kts -o /tmp/start.svg
//
// The script demonstrates:
//   1. A UML class diagram with two classes and an association.
//   2. A C4 System-Context diagram with a user, the system and one external.
//
// Both are intentionally short (~20 lines each).

import dev.kuml.c4.dsl.*

// ── 1. UML class diagram ─────────────────────────────────────────────────────

val classes = classDiagram(name = "Hello kUML") {
    val user = classOf(name = "User") {
        attribute(name = "id", type = "UUID")
        attribute(name = "email", type = "String")
    }
    val order = classOf(name = "Order") {
        attribute(name = "id", type = "UUID")
        attribute(name = "total", type = "BigDecimal")
    }
    association(source = user, target = order) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "orders" }
    }
}

// ── 2. C4 system-context diagram ─────────────────────────────────────────────

c4Model(name = "Shop") {
    val customer = person(name = "Customer") {
        description = "A shopper"
    }
    val shop = softwareSystem(name = "Shop System") {
        description = "Lets customers browse and order"
    }
    val payments = softwareSystem(name = "Payment Provider") {
        description = "Third-party payments"
        external = true
    }
    relationship(source = customer, target = shop) { technology = "HTTPS" }
    relationship(source = shop, target = payments) { technology = "HTTPS / JSON" }

    systemContextDiagram(name = "Shop — System Context") {
        include(customer, shop, payments)
    }
}

// Return the first diagram so the CLI's render command picks it up.
classes
