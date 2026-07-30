package dev.kuml.uml.dsl

import dev.kuml.core.dsl.sequenceDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.SequenceDiagramConfig
import dev.kuml.uml.InteractionOperator
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlInteraction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SequenceDiagramBuilderTest :
    FunSpec(body = {

        test(name = "empty sequence diagram has interaction with no lifelines or messages") {
            val d = sequenceDiagram(name = "Empty")
            val i = d.elements.single() as UmlInteraction
            i.lifelines.shouldBeEmpty()
            i.messages.shouldBeEmpty()
            i.fragments.shouldBeEmpty()
        }

        test(name = "lifeline has deterministic id and stores represents and isActor") {
            val d =
                sequenceDiagram(name = "PlaceOrder") {
                    lifeline(name = "Customer") {
                        isActor = true
                        represents = typeRef("User")
                    }
                }
            val ll = (d.elements.single() as UmlInteraction).lifelines.single()
            ll.id shouldBe "PlaceOrder::ll::Customer"
            ll.isActor shouldBe true
            ll.represents?.name shouldBe "User"
        }

        test(name = "messages get sequential 1-based sequence numbers in call order") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    message(from = a, to = b, label = "m1")
                    message(from = b, to = a, label = "m2")
                    message(from = a, to = b, label = "m3")
                }
            val msgs = (d.elements.single() as UmlInteraction).messages
            msgs.map { it.sequence } shouldContainExactly listOf(1, 2, 3)
            msgs.map { it.id } shouldContainExactly
                listOf(
                    "X::msg::1",
                    "X::msg::2",
                    "X::msg::3",
                )
        }

        test(name = "asyncMessage / reply / create / delete set the correct sort") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    asyncMessage(from = a, to = b, label = "fire")
                    reply(from = b, to = a, label = "ok")
                    create(from = a, to = b, label = "«create»")
                    delete(from = a, to = b, label = "«destroy»")
                }
            val sorts = (d.elements.single() as UmlInteraction).messages.map { it.sort }
            sorts shouldContainExactly
                listOf(
                    MessageSort.ASYNC_CALL,
                    MessageSort.REPLY,
                    MessageSort.CREATE,
                    MessageSort.DELETE,
                )
        }

        test(name = "alt fragment with two branches stores correct messageIds per operand") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    alt {
                        branch(guard = "[ok]") { message(from = a, to = b, label = "yes") }
                        branch(guard = "[no]") { message(from = a, to = b, label = "no") }
                    }
                }
            val i = d.elements.single() as UmlInteraction
            i.messages.map { it.label } shouldContainExactly listOf("yes", "no")
            val frag = i.fragments.single()
            frag.operator shouldBe InteractionOperator.ALT
            frag.operands.size shouldBe 2
            frag.operands[0].guard shouldBe "[ok]"
            frag.operands[0].messageIds shouldContainExactly listOf("X::msg::1")
            frag.operands[1].messageIds shouldContainExactly listOf("X::msg::2")
        }

        test(name = "opt fragment with single operand and guard") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    opt(guard = "[condition]") {
                        message(from = a, to = b, label = "optMsg")
                    }
                }
            val i = d.elements.single() as UmlInteraction
            i.messages shouldHaveSize 1
            val frag = i.fragments.single()
            frag.operator shouldBe InteractionOperator.OPT
            frag.operands shouldHaveSize 1
            frag.operands[0].guard shouldBe "[condition]"
            frag.operands[0].messageIds shouldContainExactly listOf("X::msg::1")
        }

        test(name = "loop fragment with single operand") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    loop(guard = "[hasMore]") {
                        message(from = a, to = b, label = "process")
                    }
                }
            val i = d.elements.single() as UmlInteraction
            val frag = i.fragments.single()
            frag.operator shouldBe InteractionOperator.LOOP
            frag.operands.single().guard shouldBe "[hasMore]"
            frag.operands.single().messageIds shouldContainExactly listOf("X::msg::1")
        }

        test(name = "par fragment with two branches") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    val c = lifeline(name = "C")
                    par {
                        branch { message(from = a, to = b, label = "task1") }
                        branch { message(from = a, to = c, label = "task2") }
                    }
                }
            val i = d.elements.single() as UmlInteraction
            val frag = i.fragments.single()
            frag.operator shouldBe InteractionOperator.PAR
            frag.operands shouldHaveSize 2
            frag.operands[0].messageIds shouldContainExactly listOf("X::msg::1")
            frag.operands[1].messageIds shouldContainExactly listOf("X::msg::2")
        }

        test(name = "nested fragment ids are recorded in parent operand fragmentIds") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    alt {
                        branch(guard = "[outer]") {
                            message(from = a, to = b, label = "m1")
                            opt(guard = "[inner]") {
                                message(from = a, to = b, label = "m2")
                            }
                        }
                    }
                }
            val i = d.elements.single() as UmlInteraction
            i.messages.size shouldBe 2
            i.fragments.size shouldBe 2 // outer ALT + nested OPT
            val outerAlt = i.fragments.first { it.operator == InteractionOperator.ALT }
            outerAlt.operands.single().fragmentIds shouldContainExactly listOf("X::frag::2")
            outerAlt.operands.single().messageIds shouldContainExactly listOf("X::msg::1")
            val innerOpt = i.fragments.first { it.operator == InteractionOperator.OPT }
            innerOpt.operands.single().messageIds shouldContainExactly listOf("X::msg::2")
        }

        test(name = "sequence numbers continue monotonically across fragment boundaries") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    message(from = a, to = b, label = "before")
                    alt {
                        branch {
                            message(from = a, to = b, label = "in1")
                            message(from = b, to = a, label = "in2")
                        }
                    }
                    message(from = a, to = b, label = "after")
                }
            val msgs = (d.elements.single() as UmlInteraction).messages
            msgs.map { it.sequence } shouldContainExactly listOf(1, 2, 3, 4)
        }

        test(name = "diagram type is SEQUENCE and config is SequenceDiagramConfig") {
            val d = sequenceDiagram(name = "X") { showSequenceNumbers = true }
            d.type shouldBe DiagramType.SEQUENCE
            (d.config as SequenceDiagramConfig).showSequenceNumbers shouldBe true
        }

        test(name = "interaction name equals diagram name") {
            val d = sequenceDiagram(name = "PlaceOrder")
            (d.elements.single() as UmlInteraction).name shouldBe "PlaceOrder"
        }

        test(name = "break_ fragment has correct operator") {
            val d =
                sequenceDiagram(name = "X") {
                    val a = lifeline(name = "A")
                    val b = lifeline(name = "B")
                    break_(guard = "[errorCondition]") {
                        message(from = a, to = b, label = "abort")
                    }
                }
            val frag = (d.elements.single() as UmlInteraction).fragments.single()
            frag.operator shouldBe InteractionOperator.BREAK
            frag.operands.single().guard shouldBe "[errorCondition]"
        }
    })
