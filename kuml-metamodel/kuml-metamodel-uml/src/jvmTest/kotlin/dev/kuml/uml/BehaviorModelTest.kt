package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class BehaviorModelTest :
    FunSpec(body = {

        // ── UmlInteraction (Sequence Diagram) ──────────────────────────────────────

        test(name = "minimal interaction builds with name only") {
            val interaction = UmlInteraction(id = "PlaceOrder", name = "PlaceOrder")
            interaction.lifelines.shouldBeEmpty()
            interaction.messages.shouldBeEmpty()
            interaction.fragments.shouldBeEmpty()
            interaction.shouldBeInstanceOf<UmlNamedElement>()
        }

        test(name = "interaction holds lifelines") {
            val customer = UmlLifeline(id = "PlaceOrder::ll::Customer", name = "Customer", isActor = true)
            val service =
                UmlLifeline(
                    id = "PlaceOrder::ll::OrderService",
                    name = "OrderService",
                    represents = UmlTypeRef(name = "OrderService"),
                )
            val interaction =
                UmlInteraction(
                    id = "PlaceOrder",
                    name = "PlaceOrder",
                    lifelines = listOf(customer, service),
                )
            interaction.lifelines shouldHaveSize 2
            interaction.lifelines[0].isActor shouldBe true
            interaction.lifelines[1].represents?.name shouldBe "OrderService"
        }

        test(name = "interaction holds messages in sequence order") {
            val msg1 =
                UmlMessage(
                    id = "PlaceOrder::msg::1",
                    label = "createOrder(items)",
                    fromLifelineId = "PlaceOrder::ll::Customer",
                    toLifelineId = "PlaceOrder::ll::OrderService",
                    sort = MessageSort.SYNC_CALL,
                    sequence = 1,
                )
            val msg2 =
                UmlMessage(
                    id = "PlaceOrder::msg::2",
                    label = "Order",
                    fromLifelineId = "PlaceOrder::ll::OrderService",
                    toLifelineId = "PlaceOrder::ll::Customer",
                    sort = MessageSort.REPLY,
                    sequence = 2,
                )
            val interaction =
                UmlInteraction(
                    id = "PlaceOrder",
                    name = "PlaceOrder",
                    messages = listOf(msg1, msg2),
                )
            interaction.messages shouldHaveSize 2
            interaction.messages[0].sequence shouldBe 1
            interaction.messages[0].sort shouldBe MessageSort.SYNC_CALL
            interaction.messages[1].sort shouldBe MessageSort.REPLY
        }

        test(name = "combined fragment ALT stores two operands with guards") {
            val success =
                UmlInteractionOperand(
                    guard = "[payment.success]",
                    messageIds = listOf("PlaceOrder::msg::3"),
                )
            val failure =
                UmlInteractionOperand(
                    guard = "[payment.failed]",
                    messageIds = listOf("PlaceOrder::msg::4"),
                )
            val fragment =
                UmlCombinedFragment(
                    id = "PlaceOrder::frag::1",
                    operator = InteractionOperator.ALT,
                    operands = listOf(success, failure),
                )
            fragment.operator shouldBe InteractionOperator.ALT
            fragment.operands shouldHaveSize 2
            fragment.operands[0].guard shouldBe "[payment.success]"
            fragment.operands[1].guard shouldBe "[payment.failed]"
            fragment.shouldBeInstanceOf<UmlElement>()
        }

        test(name = "MessageSort has all expected values") {
            MessageSort.entries.map { it.name } shouldBe
                listOf("SYNC_CALL", "ASYNC_CALL", "REPLY", "CREATE", "DELETE")
        }

        test(name = "InteractionOperator has all expected values") {
            InteractionOperator.entries.map { it.name } shouldBe
                listOf("ALT", "OPT", "LOOP", "PAR", "BREAK")
        }

        // ── UmlStateMachine ────────────────────────────────────────────────────────

        test(name = "minimal state machine builds with name only") {
            val sm = UmlStateMachine(id = "OrderSM", name = "OrderSM")
            sm.vertices.shouldBeEmpty()
            sm.transitions.shouldBeEmpty()
            sm.shouldBeInstanceOf<UmlNamedElement>()
        }

        test(name = "state machine holds states and transitions") {
            val initial =
                UmlPseudostate(
                    id = "OrderSM::__initial",
                    name = "__initial",
                    kind = PseudostateKind.INITIAL,
                )
            val draft = UmlState(id = "OrderSM::DRAFT", name = "DRAFT")
            val confirmed = UmlState(id = "OrderSM::CONFIRMED", name = "CONFIRMED")
            val cancelled = UmlFinalState(id = "OrderSM::CANCELLED", name = "CANCELLED")

            val t1 =
                UmlTransition(
                    id = "OrderSM::t::DRAFT->CONFIRMED",
                    sourceId = "OrderSM::DRAFT",
                    targetId = "OrderSM::CONFIRMED",
                    trigger = "confirm()",
                    guard = "[payment.success]",
                )
            val t2 =
                UmlTransition(
                    id = "OrderSM::t::CONFIRMED->CANCELLED",
                    sourceId = "OrderSM::CONFIRMED",
                    targetId = "OrderSM::CANCELLED",
                    trigger = "cancel()",
                )

            val sm =
                UmlStateMachine(
                    id = "OrderSM",
                    name = "OrderSM",
                    vertices = listOf(initial, draft, confirmed, cancelled),
                    transitions = listOf(t1, t2),
                )

            sm.vertices shouldHaveSize 4
            sm.transitions shouldHaveSize 2
            sm.transitions[0].trigger shouldBe "confirm()"
            sm.transitions[0].guard shouldBe "[payment.success]"
            sm.transitions[1].trigger shouldBe "cancel()"
            sm.transitions[1].guard shouldBe null
        }

        test(name = "UmlState is a UmlVertex and UmlNamedElement") {
            val state = UmlState(id = "SM::S", name = "S")
            state.shouldBeInstanceOf<UmlVertex>()
            state.shouldBeInstanceOf<UmlNamedElement>()
        }

        test(name = "UmlPseudostate holds its kind") {
            val ps = UmlPseudostate(id = "SM::init", name = "init", kind = PseudostateKind.INITIAL)
            ps.kind shouldBe PseudostateKind.INITIAL
            ps.shouldBeInstanceOf<UmlVertex>()
        }

        test(name = "UmlFinalState is a UmlVertex") {
            val fs = UmlFinalState(id = "SM::end", name = "end")
            fs.shouldBeInstanceOf<UmlVertex>()
        }

        test(name = "composite state holds substates") {
            val sub = UmlState(id = "SM::Outer::Inner", name = "Inner")
            val outer =
                UmlState(
                    id = "SM::Outer",
                    name = "Outer",
                    substates = listOf(sub),
                )
            outer.substates shouldHaveSize 1
            outer.substates[0].name shouldBe "Inner"
        }

        test(name = "state has entry, exit and doActivity") {
            val state =
                UmlState(
                    id = "SM::Active",
                    name = "Active",
                    entry = "startTimer()",
                    exit = "stopTimer()",
                    doActivity = "processEvents()",
                )
            state.entry shouldBe "startTimer()"
            state.exit shouldBe "stopTimer()"
            state.doActivity shouldBe "processEvents()"
        }

        test(name = "PseudostateKind has all expected values") {
            PseudostateKind.entries.map { it.name } shouldBe
                listOf("INITIAL", "CHOICE", "FORK", "JOIN", "JUNCTION", "SHALLOW_HISTORY", "DEEP_HISTORY")
        }
    })
