package dev.kuml.uml.dsl

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.StateDiagramConfig
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StateDiagramBuilderTest :
    FunSpec(body = {

        test(name = "empty state diagram builds with empty state machine") {
            val d = stateDiagram(name = "Empty")
            val sm = d.elements.single() as UmlStateMachine
            sm.vertices.shouldBeEmpty()
            sm.transitions.shouldBeEmpty()
        }

        test(name = "state has deterministic id and stores entry/exit/doActivity") {
            val d =
                stateDiagram(name = "OrderSM") {
                    state(name = "Draft") {
                        entry = "validate()"
                        exit = "cleanup()"
                        doActivity = "notify()"
                    }
                }
            val s = (d.elements.single() as UmlStateMachine).vertices.single() as UmlState
            s.id shouldBe "OrderSM::Draft"
            s.entry shouldBe "validate()"
            s.exit shouldBe "cleanup()"
            s.doActivity shouldBe "notify()"
        }

        test(name = "initialState creates pseudostate of kind INITIAL") {
            val d = stateDiagram(name = "X") { initialState() }
            val ps = (d.elements.single() as UmlStateMachine).vertices.single() as UmlPseudostate
            ps.kind shouldBe PseudostateKind.INITIAL
        }

        test(name = "finalState creates UmlFinalState") {
            val d = stateDiagram(name = "X") { finalState(name = "Done") }
            val fs = (d.elements.single() as UmlStateMachine).vertices.single()
            fs.shouldBeInstanceOf<UmlFinalState>()
            fs.name shouldBe "Done"
            fs.id shouldBe "X::Done"
        }

        test(name = "choice creates pseudostate of kind CHOICE") {
            val d = stateDiagram(name = "X") { choice(name = "PaymentOK?") }
            val ps = (d.elements.single() as UmlStateMachine).vertices.single() as UmlPseudostate
            ps.kind shouldBe PseudostateKind.CHOICE
            ps.id shouldBe "X::PaymentOK?"
        }

        test(name = "transition between two states has correct ids and labels") {
            val d =
                stateDiagram(name = "OrderSM") {
                    val draft = state(name = "Draft")
                    val confirmed = state(name = "Confirmed")
                    transition(source = draft, target = confirmed) {
                        trigger = "confirm()"
                        guard = "[valid]"
                        effect = "log()"
                    }
                }
            val sm = d.elements.single() as UmlStateMachine
            val t = sm.transitions.single()
            t.id shouldBe "OrderSM::t::Draft->Confirmed"
            t.sourceId shouldBe "OrderSM::Draft"
            t.targetId shouldBe "OrderSM::Confirmed"
            t.trigger shouldBe "confirm()"
            t.guard shouldBe "[valid]"
            t.effect shouldBe "log()"
        }

        test(name = "two transitions with same endpoints get disambiguated ids") {
            val d =
                stateDiagram(name = "X") {
                    val a = state(name = "A")
                    val b = state(name = "B")
                    transition(source = a, target = b) { trigger = "ev1" }
                    transition(source = a, target = b) { trigger = "ev2" }
                }
            val ids = (d.elements.single() as UmlStateMachine).transitions.map { it.id }
            ids shouldContainExactly listOf("X::t::A->B", "X::t::A->B~2")
        }

        test(name = "composite state stores substates under its own id") {
            val d =
                stateDiagram(name = "OrderSM") {
                    compositeState(name = "Processing") {
                        state(name = "Picking")
                        state(name = "Packing")
                    }
                }
            val composite = (d.elements.single() as UmlStateMachine).vertices.single() as UmlState
            composite.id shouldBe "OrderSM::Processing"
            composite.substates.map { it.id } shouldContainExactly
                listOf(
                    "OrderSM::Processing::Picking",
                    "OrderSM::Processing::Packing",
                )
        }

        test(name = "transition between substates of a composite is registered on the state machine") {
            val d =
                stateDiagram(name = "X") {
                    val composite =
                        compositeState(name = "Group") {
                            state(name = "A")
                            state(name = "B")
                        }
                    val a = composite.substates[0]
                    val b = composite.substates[1]
                    transition(source = a, target = b) { trigger = "next" }
                }
            val sm = d.elements.single() as UmlStateMachine
            sm.transitions.single().id shouldBe "X::t::A->B"
            sm.transitions.single().sourceId shouldBe "X::Group::A"
        }

        test(name = "diagram type is STATE and config is StateDiagramConfig") {
            val d = stateDiagram(name = "X") { showGuards = false }
            d.type shouldBe DiagramType.STATE
            (d.config as StateDiagramConfig).showGuards shouldBe false
        }

        test(name = "state machine name equals diagram name") {
            val d = stateDiagram(name = "OrderLifecycle")
            (d.elements.single() as UmlStateMachine).name shouldBe "OrderLifecycle"
        }

        test(name = "fork and join create pseudostates of correct kind") {
            val d =
                stateDiagram(name = "X") {
                    fork(name = "split")
                    join(name = "merge")
                }
            val sm = d.elements.single() as UmlStateMachine
            val vertices = sm.vertices
            vertices shouldHaveSize 2
            (vertices[0] as UmlPseudostate).kind shouldBe PseudostateKind.FORK
            (vertices[1] as UmlPseudostate).kind shouldBe PseudostateKind.JOIN
        }
    })
