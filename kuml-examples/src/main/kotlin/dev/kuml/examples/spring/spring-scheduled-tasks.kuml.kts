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
 * Spring Scheduled Tasks — Feature-Level Stereotype Example (V1.1.2)
 *
 * Illustrates the Spring profile «Scheduled» stereotype applied at
 * Operation-level: scheduled task methods carry cron expressions or
 * fixed-rate intervals as tagged values in the UML model.
 *
 * Profiles: JavaEE (kuml-profile-javaee) + Spring (kuml-profile-spring)
 */
classDiagram("Report Scheduler — Spring Tasks") {
    applyProfile(javaEeProfile)
    applyProfile(springProfile)

    // ── Scheduler Bean ────────────────────────────────────────────────────────────

    classOf("ReportScheduler") {
        stereotype("Service") { "transactional" to false }

        // ── Feature-Level: «Scheduled» on operations (V1.1.2) ────────────────────

        operation("generateDailyReport") {
            stereotype("Scheduled") {
                "cron" to "0 0 * * *"
            }
            returns("void")
        }

        operation("sendWeeklyDigest") {
            stereotype("Scheduled") {
                "cron" to "0 9 * * MON"
            }
            returns("void")
        }

        operation("pollExternalQueue") {
            stereotype("Scheduled") {
                "fixedRate" to 5000L
                "initialDelay" to 1000L
            }
            returns("void")
        }
    }
}
