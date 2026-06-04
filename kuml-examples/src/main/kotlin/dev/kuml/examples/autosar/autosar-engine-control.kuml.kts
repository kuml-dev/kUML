@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.autosar.AutosarPortDirection
import dev.kuml.profile.autosar.AutosarSwcKind
import dev.kuml.profile.autosar.autosarProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.port
import dev.kuml.uml.dsl.stereotype

/**
 * Engine Control AUTOSAR — AUTOSAR Example
 *
 * Illustrates the AUTOSAR profile applied to a powertrain control scenario
 * with a SoftwareComponent (EngineController), two AutosarPorts (rpmSensor, throttle),
 * and a ComInterface (ThrottleControl).
 *
 * Stereotyp-Name `AutosarPort` (not `Port`) is intentional per D17 — avoids
 * conflict with the UML metamodel metaclass name `Port`.
 *
 * Profile: AUTOSAR (kuml-profile-autosar, V1.1 skeleton)
 */
classDiagram(name = "Engine Control AUTOSAR") {
    applyProfile(autosarProfile)

    // ── Software Component ────────────────────────────────────────────────────

    component("EngineController") {
        stereotype("SoftwareComponent") {
            "kind" to AutosarSwcKind.Application
            "packageName" to "powertrain"
        }
        // Required port — receives RPM sensor data
        port("rpmSensor") {
            stereotype("AutosarPort") { "direction" to AutosarPortDirection.Required }
        }
        // Provided port — exposes throttle control interface
        port("throttle") {
            stereotype("AutosarPort") { "direction" to AutosarPortDirection.Provided }
        }
    }

    // ── Communication Interface ───────────────────────────────────────────────

    interfaceOf("ThrottleControl") {
        stereotype("ComInterface") {
            "version" to "2.1"
            "isService" to true
        }
    }
}
