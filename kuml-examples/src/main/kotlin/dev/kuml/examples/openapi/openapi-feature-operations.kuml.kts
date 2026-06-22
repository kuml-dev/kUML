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
classDiagram(name = "User API — OpenAPI Operation + Parameter") {
    applyProfile(openApiProfile)

    // ── REST Resource ─────────────────────────────────────────────────────────────

    classOf(name = "UserResource") {
        stereotype(name = "Resource") {
            "path" to "/users"
            "version" to "v1"
        }

        // ── Feature-Level: «Operation» on operations (V1.1.2) ────────────────────

        operation(name = "getUser") {
            stereotype(name = "Operation") {
                "method" to HttpMethod.GET
                "path" to "/users/{id}"
                "summary" to "Retrieve a user by ID"
                "status" to 200
            }
            parameter(name = "id", type = "Long") {
                stereotype(name = "Parameter") {
                    "in" to ParameterIn.Path
                    "required" to true
                }
            }
            returns(typeName = "UserSchema")
        }

        operation(name = "createUser") {
            stereotype(name = "Operation") {
                "method" to HttpMethod.POST
                "path" to "/users"
                "summary" to "Create a new user"
                "status" to 201
            }
            parameter(name = "body", type = "CreateUserRequest") {
                stereotype(name = "Parameter") {
                    "in" to ParameterIn.Body
                    "required" to true
                }
            }
            returns(typeName = "UserSchema")
        }

        operation(name = "listUsers") {
            stereotype(name = "Operation") {
                "method" to HttpMethod.GET
                "path" to "/users"
                "summary" to "List all users with optional filter"
                "status" to 200
            }
            parameter(name = "filter", type = "String") {
                stereotype(name = "Parameter") {
                    "in" to ParameterIn.Query
                    "required" to false
                }
            }
            returns(typeName = "List<UserSchema>")
        }
    }

    // ── Data Schema ───────────────────────────────────────────────────────────────

    classOf(name = "UserSchema") {
        stereotype(name = "Schema") {
            "format" to "json"
            "description" to "Public user representation"
        }
        attribute(name = "id", type = "Long")
        attribute(name = "email", type = "String")
        attribute(name = "name", type = "String")
    }
}
