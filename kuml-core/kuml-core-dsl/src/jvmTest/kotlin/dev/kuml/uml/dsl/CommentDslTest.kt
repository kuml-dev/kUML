package dev.kuml.uml.dsl

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.dsl.sequenceDiagram
import dev.kuml.core.dsl.stateDiagram
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class CommentDslTest :
    FunSpec(body = {

        // ── Class diagram ─────────────────────────────────────────────────────────

        test(name = "comment on a class diagram creates a UmlComment with no anchors") {
            val diagram =
                classDiagram(name = "Order Domain") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with a raw-id anchor creates a UmlCommentLink") {
            val diagram =
                classDiagram(name = "Order Domain") {
                    classOf(name = "Order")
                    comment(text = "Encapsulates the order lifecycle.", "Order")
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe "Order"
        }

        test(name = "comment with a builder-handle anchor resolves to the handle's id") {
            val diagram =
                classDiagram(name = "Order Domain") {
                    val order = classOf(name = "Order")
                    comment(text = "Encapsulates the order lifecycle.", order)
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe "Order"
        }

        test(name = "comment with multiple anchors creates one UmlCommentLink per anchor") {
            val diagram =
                classDiagram(name = "Order Domain") {
                    classOf(name = "Order")
                    classOf(name = "OrderItem")
                    comment(text = "Applies to both.", "Order", "OrderItem")
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 2
            links.map { it.annotatedElementId } shouldBe listOf("Order", "OrderItem")
        }

        test(name = "comment body is stored verbatim") {
            val diagram =
                classDiagram(name = "Order Domain") {
                    comment(text = "Line one\nLine two")
                }
            diagram.elements
                .filterIsInstance<UmlComment>()
                .first()
                .body shouldBe "Line one\nLine two"
        }

        test(name = "explicit id overrides the auto-derived comment id") {
            val diagram =
                classDiagram(name = "Order Domain") {
                    comment(text = "text", id = "myNote")
                }
            diagram.elements
                .filterIsInstance<UmlComment>()
                .first()
                .id shouldBe "myNote"
        }

        // ── Sequence diagram ──────────────────────────────────────────────────────

        test(name = "comment on a sequence diagram creates a UmlComment") {
            val diagram =
                sequenceDiagram(name = "PlaceOrder") {
                    lifeline(name = "Customer")
                    comment(text = "Happy path only.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
        }

        test(name = "comment on a sequence diagram with anchor creates a UmlCommentLink") {
            val diagram =
                sequenceDiagram(name = "PlaceOrder") {
                    val customer = lifeline(name = "Customer")
                    comment(text = "Entry point.", customer.id)
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe "PlaceOrder::ll::Customer"
        }

        // ── State diagram ─────────────────────────────────────────────────────────

        test(name = "comment on a state diagram creates a UmlComment") {
            val diagram =
                stateDiagram(name = "OrderSM") {
                    val draft = state(name = "DRAFT")
                    comment(text = "Entry state.", draft)
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 1
        }
    })
