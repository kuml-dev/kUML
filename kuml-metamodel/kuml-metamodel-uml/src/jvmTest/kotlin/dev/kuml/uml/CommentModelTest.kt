package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class CommentModelTest :
    FunSpec(body = {

        // ── UmlComment ────────────────────────────────────────────────────────────

        test(name = "comment stores free text body") {
            val comment =
                UmlComment(
                    id = "OrderClasses::note::1",
                    body = "Orders older than 90 days are archived nightly.",
                )
            comment.body shouldBe "Orders older than 90 days are archived nightly."
        }

        test(name = "comment is a UmlElement but not a UmlNamedElement") {
            val comment: UmlElement = UmlComment(id = "note::1", body = "free text")
            comment.shouldBeInstanceOf<UmlElement>()
            (comment is UmlNamedElement) shouldBe false
        }

        test(name = "comment supports multi-line body") {
            val comment = UmlComment(id = "note::1", body = "Line one\nLine two\nLine three")
            comment.body.lines() shouldBe listOf("Line one", "Line two", "Line three")
        }

        test(name = "comment metadata defaults to empty map") {
            val comment = UmlComment(id = "note::1", body = "text")
            comment.metadata shouldBe emptyMap()
        }

        // ── UmlCommentLink ────────────────────────────────────────────────────────

        test(name = "comment link stores comment and annotated element IDs") {
            val link =
                UmlCommentLink(
                    id = "noteanchor::note::1--Order",
                    commentId = "note::1",
                    annotatedElementId = "Order",
                )
            link.commentId shouldBe "note::1"
            link.annotatedElementId shouldBe "Order"
        }

        test(name = "comment link is a UmlRelationship and UmlElement") {
            val link =
                UmlCommentLink(id = "x", commentId = "note::1", annotatedElementId = "Order")
            link.shouldBeInstanceOf<UmlRelationship>()
            link.shouldBeInstanceOf<UmlElement>()
        }

        test(name = "a comment with multiple anchors is modelled as multiple comment links") {
            val comment = UmlComment(id = "note::1", body = "Applies to both Order and OrderItem.")
            val link1 = UmlCommentLink(id = "noteanchor::note::1--Order", commentId = comment.id, annotatedElementId = "Order")
            val link2 =
                UmlCommentLink(
                    id = "noteanchor::note::1--OrderItem",
                    commentId = comment.id,
                    annotatedElementId = "OrderItem",
                )
            listOf(link1, link2).map { it.commentId }.distinct() shouldBe listOf(comment.id)
            listOf(link1, link2).map { it.annotatedElementId } shouldBe listOf("Order", "OrderItem")
        }

        test(name = "a free-standing comment has no comment links referencing it") {
            val comment = UmlComment(id = "note::1", body = "General remark, not attached to anything.")
            val links = emptyList<UmlCommentLink>()
            links.none { it.commentId == comment.id } shouldBe true
        }
    })
