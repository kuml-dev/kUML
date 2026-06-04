package dev.kuml.profile.autosar

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/** AUTOSAR Software Component kinds. */
public enum class AutosarSwcKind { Application, Service, Sensor, Actuator }

/** AUTOSAR Port direction. */
public enum class AutosarPortDirection { Required, Provided, Both }

/** AUTOSAR behavior specification kind (V1.1.2). */
public enum class AutosarBehaviorKind { Periodic, EventTriggered, OnInit, OnShutdown }

/**
 * AUTOSAR core profile — five stereotypes (V1.1.2).
 *
 * Covers the fundamental AUTOSAR building blocks: Software Components,
 * Communication Interfaces, and Ports. V1.1.2 activates Runnable (Operation)
 * and BehaviorSpec (StateMachine) stereotypes.
 *
 * Stereotype `AutosarPort` (not `Port`) is intentional — avoids naming conflict
 * with the UML metamodel metaclass `Port` (D17).
 *
 * Reference: AUTOSAR Classic Platform R22-11
 */
public val autosarProfile: KumlProfile =
    profile("AUTOSAR") {
        namespace = "dev.kuml.profiles.autosar"
        description = "AUTOSAR Software Components, Communication, Runnables and Behavior"
        version = "1.0.0"

        // ── Software Components ───────────────────────────────────────────────────

        stereotype("SoftwareComponent") {
            extends(UmlMetaclass.Component)
            property<AutosarSwcKind>("kind") { default = AutosarSwcKind.Application }
            property<String>("packageName") { default = "" }
        }

        // ── Communication Interfaces ──────────────────────────────────────────────

        stereotype("ComInterface") {
            extends(UmlMetaclass.Interface)
            property<String>("version") { default = "1.0" }
            property<Boolean>("isService") { default = false }
        }

        // ── Ports (D17: named AutosarPort to avoid metaclass name conflict) ───────

        stereotype("AutosarPort") {
            extends(UmlMetaclass.Port)
            property<AutosarPortDirection>("direction") { default = AutosarPortDirection.Provided }
        }

        // ── Operation-Level: runnable entity (V1.1.2) ─────────────────────────────

        stereotype("Runnable") {
            extends(UmlMetaclass.Operation)
            property<AutosarBehaviorKind>("kind") { default = AutosarBehaviorKind.EventTriggered }
            property<Long>("periodMs") { default = 0L } // 0 = non-periodic
        }

        // ── StateMachine-Level: behavior specification (V1.1.2) ───────────────────

        stereotype("BehaviorSpec") {
            extends(UmlMetaclass.StateMachine)
            property<String>("specName") { default = "" }
        }
    }
