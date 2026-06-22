@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.openapi.openApiProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype

/**
 * User API — OpenAPI Example
 *
 * Illustrates the OpenAPI profile applied to a REST API surface with
 * one Resource (UserResource) and two Schemas (UserSchema, ErrorSchema).
 *
 * Profile: OpenAPI (kuml-profile-openapi, V1.1 skeleton)
 */
classDiagram(name = "User API") {
    applyProfile(openApiProfile)

    // ── REST Resource ─────────────────────────────────────────────────────────

    classOf(name = "UserResource") {
        stereotype(name = "Resource") {
            "path" to "/users"
            "version" to "v2"
        }
    }

    // ── Data Schemas ──────────────────────────────────────────────────────────

    classOf(name = "UserSchema") {
        stereotype(name = "Schema") {
            "format" to "json"
            "description" to "Public user representation"
        }
        attribute(name = "id", type = "UUID")
        attribute(name = "email", type = "String")
    }

    classOf(name = "ErrorSchema") {
        stereotype(name = "Schema") {
            "format" to "json"
            "description" to "Standard RFC-7807 error body"
        }
        attribute(name = "type", type = "String")
        attribute(name = "title", type = "String")
        attribute(name = "status", type = "Int")
    }
}
