package dev.kuml.profile.autosar

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/** AUTOSAR Adaptive Platform element kinds. */
public enum class AutosarAdaptiveKind {
    AdaptiveApplication,
    Machine,
    ServiceInstance,
    Manifest,
}

/**
 * AUTOSAR Adaptive Platform profile — four stereotypes (V3.1.35).
 *
 * Extends the conceptual AUTOSAR domain with stereotypes specific to the Adaptive Platform:
 * - **AdaptiveApplication**: Adaptive Application Software Component (`ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE`)
 * - **Machine**: Machine Design element (`MACHINE-DESIGN`)
 * - **ServiceInstance**: Service Instance manifest (`SERVICE-INSTANCE`)
 * - **Manifest**: Service or Machine manifest (`SERVICE-MANIFEST` / `MACHINE-MANIFEST`)
 *
 * The existing [autosarProfile] (5 Classic stereotypes) is **not** mutated.
 * This is a separate [KumlProfile] that conceptually extends Autosar for Adaptive Platform use.
 *
 * Reference: AUTOSAR Adaptive Platform R23-11
 */
public val autosarAdaptiveProfile: KumlProfile =
    profile("AUTOSAR-Adaptive") {
        namespace = "dev.kuml.profiles.autosar.adaptive"
        description = "AUTOSAR Adaptive Platform Software Components, Machines, Services and Manifests"
        version = "1.0.0"

        // ── Adaptive Application Software Component ───────────────────────────

        stereotype("AdaptiveApplication") {
            extends(UmlMetaclass.Component)
            property<String>("appName") { default = "" }
        }

        // ── Machine Design ────────────────────────────────────────────────────

        stereotype("Machine") {
            extends(UmlMetaclass.Component)
        }

        // ── Service Instance ──────────────────────────────────────────────────

        stereotype("ServiceInstance") {
            extends(UmlMetaclass.Component)
            property<String>("instanceId") { default = "" }
        }

        // ── Manifest (Service or Machine) ─────────────────────────────────────

        stereotype("Manifest") {
            extends(UmlMetaclass.Class)
            property<String>("manifestKind") { default = "service" }
        }
    }
