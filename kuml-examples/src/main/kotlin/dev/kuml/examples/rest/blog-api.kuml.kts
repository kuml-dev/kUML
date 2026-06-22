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
classDiagram(name = "Blog API") {

    // ── Entities ──────────────────────────────────────────────────────────────

    classOf(name = "User") {
        attribute(name = "id", type = "UUID")
        attribute(name = "username", type = "String")
        attribute(name = "email", type = "String")
        attribute(name = "createdAt", type = "LocalDate")
    }

    classOf(name = "Post") {
        attribute(name = "id", type = "UUID")
        attribute(name = "title", type = "String")
        attribute(name = "content", type = "String")
        attribute(name = "publishedAt", type = "LocalDate")
    }

    classOf(name = "Comment") {
        attribute(name = "id", type = "UUID")
        attribute(name = "text", type = "String")
        attribute(name = "createdAt", type = "LocalDate")
    }

    classOf(name = "Tag") {
        attribute(name = "id", type = "UUID")
        attribute(name = "name", type = "String")
    }
}

// Run: kuml transform blog-api.kuml.kts --transformer uml-to-rest --output build/rest-gen/
