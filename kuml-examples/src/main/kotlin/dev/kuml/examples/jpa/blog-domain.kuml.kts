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
classDiagram("Blog Domain") {

    // ── Entities ──────────────────────────────────────────────────────────────

    val user =
        classOf("User") {
            attribute("id", type = "Long")
            attribute("username", type = "String")
            attribute("email", type = "String")
            attribute("createdAt", type = "String") // simplified — no date type in MVP
        }

    val post =
        classOf("Post") {
            attribute("id", type = "Long")
            attribute("title", type = "String")
            attribute("content", type = "String")
        }

    val comment =
        classOf("Comment") {
            attribute("id", type = "Long")
            attribute("text", type = "String")
        }

    val tag =
        classOf("Tag") {
            attribute("id", type = "Long")
            attribute("name", type = "String")
        }

    // ── Associations (source → target, multiplicity on target end) ────────────

    association(source = user, target = post) {
        target { multiplicity("0..*") }
    }

    association(source = post, target = comment) {
        target { multiplicity("0..*") }
    }

    association(source = post, target = tag) {
        target { multiplicity("0..*") }
    }
}
