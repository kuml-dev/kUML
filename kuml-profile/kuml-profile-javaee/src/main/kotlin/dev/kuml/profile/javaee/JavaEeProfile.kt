package dev.kuml.profile.javaee

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/**
 * Java Enterprise Edition / Jakarta EE core profile — five stereotypes (V1.1.2).
 *
 * Provides the persistence, service-layer and web stereotypes for V1.1.
 * First real application of tagged-value Properties (D11) with typed defaults.
 * V1.1.2 adds [PersistenceContext] on Property-level (field injection annotation).
 *
 * Reference: Jakarta EE Platform Specification 10 / JPA 3.1 / JAX-RS 3.1
 */
public val javaEeProfile: KumlProfile =
    profile("JavaEE") {
        namespace = "dev.kuml.profiles.javaee"
        description = "Java Enterprise Edition / Jakarta EE — Persistenz, Service-Layer, Web"
        version = "1.0.0"

        // ── Persistence ───────────────────────────────────────────────────────────

        stereotype("Entity") {
            extends(UmlMetaclass.Class)
            property<String>("tableName") // required — no default
            property<String>("schema") { default = "public" }
            property<Boolean>("cacheable") { default = false }
        }

        stereotype("Repository") {
            extends(UmlMetaclass.Class)
            property<String>("dataSource") { default = "default" }
        }

        // ── Property-Level: JPA EntityManager injection (V1.1.2) ─────────────────

        stereotype("PersistenceContext") {
            extends(UmlMetaclass.Property)
            property<String>("unitName") { default = "default" }
            property<String>("type") { default = "TRANSACTION" } // TRANSACTION | EXTENDED
        }

        // ── Service Layer ─────────────────────────────────────────────────────────

        stereotype("Service") {
            extends(UmlMetaclass.Class)
            property<Boolean>("transactional") { default = true }
        }

        // ── Web Layer ─────────────────────────────────────────────────────────────

        stereotype("Controller") {
            extends(UmlMetaclass.Class)
            property<String>("requestMapping") { default = "/" }
        }
    }
