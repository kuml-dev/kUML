package dev.kuml.profile.erm

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/**
 * ERM mapping profile — seven stereotypes that let a UML class-diagram author
 * override the defaults [dev.kuml.transform.umlerm.UmlToErmTransformer]
 * (module `kuml-transform-uml-to-erm`, V3.4.6) would otherwise derive purely
 * from naming conventions (snake_case + pluralisation) and structural
 * heuristics (PK detection, inheritance strategy, M:N junction naming).
 *
 * Deliberately does **not** extend [dev.kuml.profile.javaee.javaEeProfile] —
 * unlike [dev.kuml.profile.exposed.exposedProfile] (ADR-0016 Variante A), this
 * profile targets the ERM (PSM) metamodel, not a dual-apply JavaEE/Exposed
 * annotation style, so it stays dependency-free of the JavaEE profile module.
 *
 * Enum-shaped values ([dev.kuml.erm.model.ReferentialAction], the transformer's
 * `InheritanceStrategy`) are modelled as plain `String` tags rather than
 * `EnumVal` — [dev.kuml.profile.builder.PropertyBuilder] needs a real Kotlin
 * enum `KClass` for that, which would pull `kuml-metamodel-erm` (and its
 * transitive KMP targets) into this profile module. The transformer maps the
 * raw strings onto its own enums and rejects unrecognised values.
 *
 * V3.4.6
 */
public val ermMappingProfile: KumlProfile =
    profile("ErmMapping") {
        namespace = ErmProfileNames.NAMESPACE
        description = "ERM mapping overrides — table/inheritance/junction naming for UML → ERM M2M (V3.4.6)"
        version = "1.0.0"

        // ── Class-level ───────────────────────────────────────────────────────

        stereotype(ErmProfileNames.ENTITY) {
            extends(UmlMetaclass.Class)
            property<String>(ErmProfileNames.TAG_TABLE_NAME) // required — no default
            property<String>(ErmProfileNames.TAG_SCHEMA) { default = "public" }
            property<String>(ErmProfileNames.TAG_KOTLIN_OBJECT_NAME) { required = false } // optional override — no default
        }

        stereotype(ErmProfileNames.INHERITANCE) {
            extends(UmlMetaclass.Class)
            // SINGLE_TABLE | JOINED | TABLE_PER_CLASS — see InheritanceStrategy in the transformer module.
            property<String>(ErmProfileNames.TAG_STRATEGY) { default = "JOINED" }
            property<String>(ErmProfileNames.TAG_DISCRIMINATOR_COLUMN) { default = "dtype" }
        }

        // ── Property-level ────────────────────────────────────────────────────

        stereotype(ErmProfileNames.COLUMN) {
            extends(UmlMetaclass.Property)
            property<String>(ErmProfileNames.TAG_COLUMN_NAME) // required — no default
            property<String>(ErmProfileNames.TAG_SQL_TYPE) { default = "" }
            property<String>(ErmProfileNames.TAG_ENUM_TYPE) { required = false } // optional override — no default
            // Pins an explicit FK target (by UML class name, and optionally a target ERM column
            // name — defaults to the target's primary key) directly on a plain attribute, bypassing
            // UmlAssociation-based FK derivation entirely. Closes the gap where the real column name
            // (e.g. "created_by") doesn't match what association-to-FK naming would derive (e.g.
            // "member_id") and there is no role-based way to override it (see UmlToErmTransformer's
            // "Known limitations" KDoc).
            property<String>(ErmProfileNames.TAG_FK_ENTITY) { required = false } // optional — no default
            property<String>(ErmProfileNames.TAG_FK_ATTRIBUTE) { required = false } // optional — no default
            property<Boolean>(ErmProfileNames.TAG_NULLABLE) { default = true }
            property<Boolean>(ErmProfileNames.TAG_UNIQUE) { default = false }
        }

        stereotype(ErmProfileNames.ID) {
            extends(UmlMetaclass.Property)
            property<Boolean>(ErmProfileNames.TAG_AUTO_INCREMENT) { default = true }
        }

        stereotype(ErmProfileNames.TRANSIENT) {
            extends(UmlMetaclass.Property)
        }

        // ── Association-level ─────────────────────────────────────────────────

        stereotype(ErmProfileNames.FK) {
            extends(UmlMetaclass.Association)
            property<String>(ErmProfileNames.TAG_CONSTRAINT_NAME) { default = "" }
            property<String>(ErmProfileNames.TAG_ON_DELETE) { default = "NO_ACTION" }
            property<String>(ErmProfileNames.TAG_ON_UPDATE) { default = "NO_ACTION" }
        }

        stereotype(ErmProfileNames.JUNCTION_TABLE) {
            extends(UmlMetaclass.Association)
            property<String>(ErmProfileNames.TAG_TABLE_NAME) // required — no default
            property<String>(ErmProfileNames.TAG_SOURCE_COLUMN) { default = "" }
            property<String>(ErmProfileNames.TAG_TARGET_COLUMN) { default = "" }
        }
    }
