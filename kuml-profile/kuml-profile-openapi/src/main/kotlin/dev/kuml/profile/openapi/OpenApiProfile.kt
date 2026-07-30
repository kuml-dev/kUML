package dev.kuml.profile.openapi

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/** HTTP method for an OpenAPI operation. */
public enum class HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }

/** Location of a parameter in an OpenAPI operation. */
public enum class ParameterIn { Path, Query, Header, Cookie, Body }

/**
 * OpenAPI / REST profile — four stereotypes (V1.1.2, activated in V1.1.2).
 *
 * Covers the full Operation + Parameter surface alongside the V1.1 class-level
 * Resource and Schema stereotypes.
 *
 * Reference: OpenAPI Specification 3.1
 */
public val openApiProfile: KumlProfile =
    profile(name = "OpenAPI") {
        namespace = "dev.kuml.profiles.openapi"
        description = "OpenAPI / REST resources, schemas, operations and parameters"
        version = "1.0.0"

        // ── REST Resources ────────────────────────────────────────────────────────

        stereotype(name = "Resource") {
            extends(UmlMetaclass.Class)
            property<String>(name = "path")
            property<String>(name = "version") { default = "v1" }
        }

        // ── Data Schemas ──────────────────────────────────────────────────────────

        stereotype(name = "Schema") {
            extends(UmlMetaclass.Class)
            property<String>(name = "format") { default = "json" }
            property<String>(name = "description") { default = "" }
        }

        // ── Operation-Level: REST operation mapping (V1.1.2) ──────────────────────

        stereotype(name = "Operation") {
            extends(UmlMetaclass.Operation)
            property<HttpMethod>(name = "method") { default = HttpMethod.GET }
            property<String>(name = "path") { default = "/" }
            property<String>(name = "summary") { default = "" }
            property<Int>(name = "status") { default = 200 }
        }

        // ── Parameter-Level: REST parameter binding (V1.1.2) ──────────────────────

        stereotype(name = "Parameter") {
            extends(UmlMetaclass.Parameter)
            property<ParameterIn>(name = "in") { default = ParameterIn.Query }
            property<Boolean>(name = "required") { default = false }
        }
    }
