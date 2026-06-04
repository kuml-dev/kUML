@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.openapi.HttpMethod
import dev.kuml.profile.openapi.ParameterIn
import dev.kuml.profile.openapi.openApiProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype

/**
 * OpenAPI Feature Operations — Feature-Level Stereotype Example (V1.1.2)
 *
 * Illustrates the OpenAPI profile «Operation» and «Parameter» stereotypes
 * applied at Operation-level and Parameter-level respectively.
 * HTTP method, path, and parameter binding are modelled as tagged values.
 *
 * Profile: OpenAPI (kuml-profile-openapi)
 */
classDiagram("User API — OpenAPI Operation + Parameter") {
    applyProfile(openApiProfile)

    // ── REST Resource ─────────────────────────────────────────────────────────────

    classOf("UserResource") {
        stereotype("Resource") {
            "path" to "/users"
            "version" to "v1"
        }

        // ── Feature-Level: «Operation» on operations (V1.1.2) ────────────────────

        operation("getUser") {
            stereotype("Operation") {
                "method" to HttpMethod.GET
                "path" to "/users/{id}"
                "summary" to "Retrieve a user by ID"
                "status" to 200
            }
            parameter("id", "Long") {
                stereotype("Parameter") {
                    "in" to ParameterIn.Path
                    "required" to true
                }
            }
            returns("UserSchema")
        }

        operation("createUser") {
            stereotype("Operation") {
                "method" to HttpMethod.POST
                "path" to "/users"
                "summary" to "Create a new user"
                "status" to 201
            }
            parameter("body", "CreateUserRequest") {
                stereotype("Parameter") {
                    "in" to ParameterIn.Body
                    "required" to true
                }
            }
            returns("UserSchema")
        }

        operation("listUsers") {
            stereotype("Operation") {
                "method" to HttpMethod.GET
                "path" to "/users"
                "summary" to "List all users with optional filter"
                "status" to 200
            }
            parameter("filter", "String") {
                stereotype("Parameter") {
                    "in" to ParameterIn.Query
                    "required" to false
                }
            }
            returns("List<UserSchema>")
        }
    }

    // ── Data Schema ───────────────────────────────────────────────────────────────

    classOf("UserSchema") {
        stereotype("Schema") {
            "format" to "json"
            "description" to "Public user representation"
        }
        attribute(name = "id", type = "Long")
        attribute(name = "email", type = "String")
        attribute(name = "name", type = "String")
    }
}
