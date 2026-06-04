package dev.kuml.profile.spring

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile
import dev.kuml.profile.javaee.javaEeProfile

/**
 * Spring Framework core profile — two class-level stereotypes.
 *
 * Extends [javaEeProfile] (D12/D13): RestController specializes Controller,
 * SpringData specializes Repository. First real test of Profil-Vererbung and
 * Stereotyp-Spezialisierung (D12).
 *
 * Reference: Spring Framework 6.x / Spring Data 3.x
 */
public val springProfile: KumlProfile =
    profile("Spring") {
        namespace = "dev.kuml.profiles.spring"
        description = "Spring Framework — REST, Repository-Spezialisierungen"
        version = "1.0.0"
        extends(javaEeProfile) // D12: Profil-Vererbung

        // ── Web Layer ─────────────────────────────────────────────────────────────

        stereotype("RestController") {
            extends(UmlMetaclass.Class)
            specializes = "Controller" // D12: Stereotyp-Spezialisierung
            property<String>("produces") { default = "application/json" }
            property<String>("consumes") { default = "application/json" }
        }

        // ── Data Layer ────────────────────────────────────────────────────────────

        stereotype("SpringData") {
            extends(UmlMetaclass.Class)
            specializes = "Repository"
            property<Boolean>("readOnly") { default = false }
        }
    }
