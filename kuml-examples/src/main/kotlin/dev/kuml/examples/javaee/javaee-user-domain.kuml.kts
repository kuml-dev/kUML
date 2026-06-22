@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.javaee.javaEeProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype

/**
 * User Domain — JavaEE Profile Example (V1.1)
 *
 * Illustrates the JavaEE profile applied to a simple user management domain:
 * Entity + Repository + Service + Controller, all with Tagged-Values (D11).
 *
 * Profile: JavaEE (kuml-profile-javaee)
 */
classDiagram(name = "User Domain") {
    applyProfile(javaEeProfile)

    // ── Persistence Layer ─────────────────────────────────────────────────────────

    classOf(name = "User") {
        stereotype(name = "Entity") {
            "tableName" to "users"
            "schema" to "auth"
            "cacheable" to true
        }
        attribute(name = "id", type = "UUID")
        attribute(name = "email", type = "String")
        attribute(name = "name", type = "String")
    }

    classOf(name = "UserRepository") {
        stereotype(name = "Repository") { "dataSource" to "userDb" }
        operation(name = "findById") { returns(typeName = "User") }
        operation(name = "save") { returns(typeName = "User") }
    }

    // ── Service Layer ─────────────────────────────────────────────────────────────

    classOf(name = "UserService") {
        stereotype(name = "Service") { "transactional" to true }
        operation(name = "register") { returns(typeName = "User") }
    }

    // ── Web Layer ─────────────────────────────────────────────────────────────────

    classOf(name = "UserController") {
        stereotype(name = "Controller") { "requestMapping" to "/api/users" }
        operation(name = "list") { returns(typeName = "List<User>") }
    }
}
