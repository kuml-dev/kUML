@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf

/**
 * Blog Domain — UML class diagram for JPA code generation (V2.0.21).
 *
 * Run:
 * ```
 * kuml transform blog-domain.kuml.kts --transformer uml-to-jpa --output build/jpa-gen/
 * ```
 *
 * Produces four Kotlin JPA entity files:
 *   User.kt, Post.kt, Comment.kt, Tag.kt
 */
classDiagram(name = "Blog Domain") {

    // ── Entities ──────────────────────────────────────────────────────────────

    val user =
        classOf(name = "User") {
            attribute(name = "id", type = "Long")
            attribute(name = "username", type = "String")
            attribute(name = "email", type = "String")
            attribute(name = "createdAt", type = "String") // simplified — no date type in MVP
        }

    val post =
        classOf(name = "Post") {
            attribute(name = "id", type = "Long")
            attribute(name = "title", type = "String")
            attribute(name = "content", type = "String")
        }

    val comment =
        classOf(name = "Comment") {
            attribute(name = "id", type = "Long")
            attribute(name = "text", type = "String")
        }

    val tag =
        classOf(name = "Tag") {
            attribute(name = "id", type = "Long")
            attribute(name = "name", type = "String")
        }

    // ── Associations (source → target, multiplicity on target end) ────────────

    association(source = user, target = post) {
        target { multiplicity(spec = "0..*") }
    }

    association(source = post, target = comment) {
        target { multiplicity(spec = "0..*") }
    }

    association(source = post, target = tag) {
        target { multiplicity(spec = "0..*") }
    }
}
