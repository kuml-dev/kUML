@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf

/**
 * Blog API — UML class diagram for OpenAPI 3.0 YAML generation (V2.0.22).
 *
 * Uses the same blog-domain model as the JPA example (User, Post, Comment, Tag)
 * but targets the `uml-to-rest` M2M transformer to produce an OpenAPI 3.0 spec.
 *
 * Run:
 * ```
 * kuml transform blog-api.kuml.kts --transformer uml-to-rest --output build/rest-gen/
 * ```
 *
 * Produces: build/rest-gen/openapi.yaml
 *   - 4 JSON Schema components (User, Post, Comment, Tag)
 *   - 20 CRUD paths (5 per class)
 */
classDiagram("Blog API") {

    // ── Entities ──────────────────────────────────────────────────────────────

    classOf("User") {
        attribute("id", type = "UUID")
        attribute("username", type = "String")
        attribute("email", type = "String")
        attribute("createdAt", type = "LocalDate")
    }

    classOf("Post") {
        attribute("id", type = "UUID")
        attribute("title", type = "String")
        attribute("content", type = "String")
        attribute("publishedAt", type = "LocalDate")
    }

    classOf("Comment") {
        attribute("id", type = "UUID")
        attribute("text", type = "String")
        attribute("createdAt", type = "LocalDate")
    }

    classOf("Tag") {
        attribute("id", type = "UUID")
        attribute("name", type = "String")
    }
}

// Run: kuml transform blog-api.kuml.kts --transformer uml-to-rest --output build/rest-gen/
