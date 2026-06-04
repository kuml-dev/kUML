package dev.kuml.profile.openapi

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/**
 * OpenAPI / REST profile — two class-level stereotypes (V1.1 skeleton).
 *
 * Provides Resource and Schema stereotypes for modelling REST API surfaces.
 * Operation and Parameter stereotypes are deferred to V1.1.2 (D14).
 *
 * Reference: OpenAPI Specification 3.1
 */
public val openApiProfile: KumlProfile =
    profile("OpenAPI") {
        namespace = "dev.kuml.profiles.openapi"
        description = "OpenAPI / REST resources and schemas (V1.1 skeleton)"
        version = "1.0.0"

        // ── REST Resources ────────────────────────────────────────────────────────

        stereotype("Resource") {
            extends(UmlMetaclass.Class)
            property<String>("path")
            property<String>("version") { default = "v1" }
        }

        // ── Data Schemas ──────────────────────────────────────────────────────────

        stereotype("Schema") {
            extends(UmlMetaclass.Class)
            property<String>("format") { default = "json" }
            property<String>("description") { default = "" }
        }
    }
