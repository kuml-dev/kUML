package dev.kuml.profile.exposed

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile
import dev.kuml.profile.javaee.javaEeProfile

/**
 * Kotlin Exposed ORM profile (ADR-0016, Variante A) — three stereotypes.
 *
 * Provides the class-level `«Table»`, property-level `«Column»`, and
 * association-level `«FK»` stereotypes used by [dev.kuml.codegen.m2m.exposed.UmlToExposedPsmTransformer]
 * to annotate a UML PIM into a renderable, Exposed-flavoured PSM.
 *
 * Extends [javaEeProfile] (D12: Profil-Vererbung) — the Exposed persistence
 * concept sits conceptually alongside JavaEE's `Entity`/`Repository` stereotypes,
 * but does NOT specialize `Entity` (D12 `specializes`): `«Table»` and `«Entity»`
 * are applied side-by-side (dual-apply) on the same class by the PSM transformer,
 * not related via stereotype specialization — see [dev.kuml.codegen.m2m.exposed.UmlToExposedPsmTransformer]
 * KDoc for the rationale (kuml-gen-sql only recognizes the literal name `"Entity"`).
 *
 * Reference: JetBrains Exposed 0.5x, `org.jetbrains.exposed.sql.Table` DSL.
 */
public val exposedProfile: KumlProfile =
    profile("Exposed") {
        namespace = "dev.kuml.profiles.exposed"
        description = "Kotlin Exposed ORM — Table/Column/FK-Stereotypen für die MDA-Persistenzschicht (ADR-0016)"
        version = "1.0.0"
        extends(javaEeProfile) // D12: Profil-Vererbung (kein specializes — siehe KDoc oben)

        // ── Class-Level: renderable Exposed Table ────────────────────────────────

        stereotype("Table") {
            extends(UmlMetaclass.Class)
            property<String>("tableName") // required — no default
            property<String>("schema") { default = "public" }
        }

        // ── Property-Level: Exposed column ───────────────────────────────────────

        stereotype("Column") {
            extends(UmlMetaclass.Property)
            property<String>("columnType") // required — Exposed DSL fn name, e.g. "varchar"/"long"/"integer"/"bool"/"double"
            property<String>("name") // required — actual DB column name (kuml-gen-sql-compatible tag key)
        }

        // ── Association-Level: cosmetic FK marker ────────────────────────────────

        stereotype("FK") {
            extends(UmlMetaclass.Association)
            property<String>("targetTable") // required — cosmetic only, not consumed by kuml-gen-sql
        }
    }
