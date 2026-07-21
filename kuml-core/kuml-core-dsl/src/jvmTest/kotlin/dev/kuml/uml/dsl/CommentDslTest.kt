package dev.kuml.uml.dsl

import dev.kuml.core.dsl.activityDiagram
import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.dsl.communicationDiagram
import dev.kuml.core.dsl.componentDiagram
import dev.kuml.core.dsl.compositeStructureDiagram
import dev.kuml.core.dsl.deploymentDiagram
import dev.kuml.core.dsl.interactionOverviewDiagram
import dev.kuml.core.dsl.objectDiagram
import dev.kuml.core.dsl.packageDiagram
import dev.kuml.core.dsl.profileDiagram
import dev.kuml.core.dsl.sequenceDiagram
import dev.kuml.core.dsl.stateDiagram
import dev.kuml.core.dsl.timingDiagram
import dev.kuml.core.dsl.useCaseDiagram
import dev.kuml.uml.UmlClass
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

        // ── Component diagram ─────────────────────────────────────────────────────

        test(name = "comment on a component diagram creates a UmlComment") {
            val diagram =
                componentDiagram(name = "Architecture") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a component diagram creates a UmlCommentLink") {
            val diagram =
                componentDiagram(name = "Architecture") {
                    val orderService = component(name = "OrderService")
                    comment(text = "Handles order lifecycle.", anchors = arrayOf(orderService.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe "OrderService"
        }

        // ── Use-case diagram ──────────────────────────────────────────────────────

        test(name = "comment on a use-case diagram creates a UmlComment") {
            val diagram =
                useCaseDiagram(name = "Checkout") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a use-case diagram creates a UmlCommentLink") {
            val diagram =
                useCaseDiagram(name = "Checkout") {
                    val placeOrder = useCase(name = "Place Order")
                    comment(text = "Core happy-path use case.", anchors = arrayOf(placeOrder.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlUseCase>()
                    .first()
                    .id
        }

        // ── Object diagram ────────────────────────────────────────────────────────

        test(name = "comment on an object diagram creates a UmlComment") {
            val diagram =
                objectDiagram(name = "Order #42 snapshot") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on an object diagram creates a UmlCommentLink") {
            val customerClassifier = UmlClass(id = "Customer", name = "Customer")
            val diagram =
                objectDiagram(name = "Order #42 snapshot") {
                    val alice = instanceOf(classifier = customerClassifier, name = "alice")
                    comment(text = "Snapshot of a returning customer.", anchors = arrayOf(alice.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlInstanceSpecification>()
                    .first()
                    .id
        }

        // ── Package diagram ───────────────────────────────────────────────────────

        test(name = "comment on a package diagram creates a UmlComment") {
            val diagram =
                packageDiagram(name = "Modules") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a package diagram creates a UmlCommentLink") {
            val diagram =
                packageDiagram(name = "Modules") {
                    val domain = packageOf(name = "Domain")
                    comment(text = "Core domain package.", anchors = arrayOf(domain.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlPackage>()
                    .first()
                    .id
        }

        // ── Deployment diagram ────────────────────────────────────────────────────

        test(name = "comment on a deployment diagram creates a UmlComment") {
            val diagram =
                deploymentDiagram(name = "Production Topology") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a deployment diagram creates a UmlCommentLink") {
            val diagram =
                deploymentDiagram(name = "Production Topology") {
                    val server = node(name = "AppServer")
                    comment(text = "Hosts the API.", anchors = arrayOf(server.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlNode>()
                    .first()
                    .id
        }

        // ── Profile diagram ───────────────────────────────────────────────────────

        test(name = "comment on a profile diagram creates a UmlComment") {
            val diagram =
                profileDiagram(name = "Domain Profile") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a profile diagram creates a UmlCommentLink") {
            val diagram =
                profileDiagram(name = "Domain Profile") {
                    val entity = stereotype(name = "Entity")
                    comment(text = "Marks persistent domain objects.", anchors = arrayOf(entity.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlStereotype>()
                    .first()
                    .id
        }

        // ── Composite-structure diagram ───────────────────────────────────────────

        test(name = "comment on a composite-structure diagram creates a UmlComment") {
            val diagram =
                compositeStructureDiagram(name = "Order Structure") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a composite-structure diagram creates a UmlCommentLink") {
            val diagram =
                compositeStructureDiagram(name = "Order Structure") {
                    val part = component(name = "PaymentPart")
                    comment(text = "Handles payment processing.", anchors = arrayOf(part.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlComponent>()
                    .first()
                    .id
        }

        // ── Activity diagram ──────────────────────────────────────────────────────

        test(name = "comment on an activity diagram creates a UmlComment") {
            val diagram =
                activityDiagram(name = "Order Fulfillment") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on an activity diagram creates a UmlCommentLink") {
            val diagram =
                activityDiagram(name = "Order Fulfillment") {
                    val ship = action(name = "Ship Order")
                    comment(text = "Triggers the carrier integration.", anchors = arrayOf(ship.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlActivityNode>()
                    .first()
                    .id
        }

        // ── Communication diagram ─────────────────────────────────────────────────

        test(name = "comment on a communication diagram creates a UmlComment") {
            val diagram =
                communicationDiagram(name = "Place Order") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a communication diagram creates a UmlCommentLink") {
            val diagram =
                communicationDiagram(name = "Place Order") {
                    val customer = role(classifierName = "Customer", roleName = "c")
                    comment(text = "Initiates the interaction.", anchors = arrayOf(customer.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlInstanceSpecification>()
                    .first()
                    .id
        }

        // ── Timing diagram ────────────────────────────────────────────────────────

        test(name = "comment on a timing diagram creates a UmlComment") {
            val diagram =
                timingDiagram(name = "Signal Timing") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on a timing diagram creates a UmlCommentLink") {
            val diagram =
                timingDiagram(name = "Signal Timing") {
                    val signal = lifeline(name = "Signal", states = listOf("low", "high"))
                    comment(text = "Rises on trigger.", anchors = arrayOf(signal.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlTimingLifeline>()
                    .first()
                    .id
        }

        // ── Interaction-overview diagram ──────────────────────────────────────────

        test(name = "comment on an interaction-overview diagram creates a UmlComment") {
            val diagram =
                interactionOverviewDiagram(name = "Checkout Overview") {
                    comment(text = "General remark, not attached to anything.")
                }
            diagram.elements.filterIsInstance<UmlComment>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlCommentLink>() shouldHaveSize 0
        }

        test(name = "comment with anchor on an interaction-overview diagram creates a UmlCommentLink") {
            val diagram =
                interactionOverviewDiagram(name = "Checkout Overview") {
                    val ref = interactionRef(name = "PlaceOrder")
                    comment(text = "Delegates to the sequence diagram.", anchors = arrayOf(ref.id))
                }
            val links = diagram.elements.filterIsInstance<UmlCommentLink>()
            links shouldHaveSize 1
            links.first().annotatedElementId shouldBe
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlInteractionOverviewFrame>()
                    .first()
                    .id
        }
    })
