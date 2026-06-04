@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.autosar.AutosarBehaviorKind
import dev.kuml.profile.autosar.AutosarPortDirection
import dev.kuml.profile.autosar.AutosarSwcKind
import dev.kuml.profile.autosar.autosarProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.port
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype

/**
 * AUTOSAR Runnable — Feature-Level Stereotype Example (V1.1.2)
 *
 * Illustrates the AUTOSAR profile «Runnable» stereotype applied at
 * Operation-level: runnable entities carry timing and behavior-kind
 * metadata as tagged values in the UML model.
 *
 * In this example:
 * - BrakeControllerSwc is a «SoftwareComponent» (Component) with ports
 * - BrakeControllerImpl is a plain class modelling the operations with «Runnable» stereotypes
 *
 * Note: «BehaviorSpec» targets StateMachine-level. The DSL currently does
 * not expose stereotype() at the stateDiagram { } root scope for the
 * UmlStateMachine itself. BehaviorSpec is declared in the profile and
 * validated via profile tests — full DSL wiring is tracked for V1.1.3.
 *
 * Profile: AUTOSAR (kuml-profile-autosar)
 */
classDiagram("Brake Controller — AUTOSAR Runnables") {
    applyProfile(autosarProfile)

    // ── Software Component (component-level stereotype) ───────────────────────────

    component("BrakeControllerSwc") {
        stereotype("SoftwareComponent") {
            "kind" to AutosarSwcKind.Application
            "packageName" to "chassis.brake"
        }
        port("rpmSensor") {
            stereotype("AutosarPort") { "direction" to AutosarPortDirection.Required }
        }
        port("brakeCmd") {
            stereotype("AutosarPort") { "direction" to AutosarPortDirection.Provided }
        }
    }

    // ── Implementation class with Runnable operations (feature-level stereotype) ──

    classOf("BrakeControllerImpl") {
        // ── Feature-Level: «Runnable» on operations (V1.1.2) ─────────────────────
        operation("onCycle") {
            stereotype("Runnable") {
                "kind" to AutosarBehaviorKind.Periodic
                "periodMs" to 10L // 10 ms cycle time
            }
            returns("void")
        }

        operation("onBrakePedalEvent") {
            stereotype("Runnable") {
                "kind" to AutosarBehaviorKind.EventTriggered
                "periodMs" to 0L
            }
            returns("void")
        }

        operation("onInit") {
            stereotype("Runnable") {
                "kind" to AutosarBehaviorKind.OnInit
                "periodMs" to 0L
            }
            returns("void")
        }
    }

    // ── Communication Interface ───────────────────────────────────────────────────

    interfaceOf("BrakePedalInterface") {
        stereotype("ComInterface") {
            "version" to "3.0"
            "isService" to false
        }
    }
}
