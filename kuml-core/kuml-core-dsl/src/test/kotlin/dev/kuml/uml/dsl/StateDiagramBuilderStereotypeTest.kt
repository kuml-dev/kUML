package dev.kuml.uml.dsl

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * V1.1.3 Ticket 2 — verifies that the state-machine root scope accepts
 * `stereotype("...")` applications and that they land on the constructed
 * [UmlStateMachine] via its `appliedStereotypes` slot.
 */
class StateDiagramBuilderStereotypeTest :
    FunSpec(body = {

        // Test fixture — minimal profile for state-machine stereotypes.
        val testProfile: KumlProfile =
            profile("StateMachineTest") {
                namespace = "dev.kuml.test.profiles.statemachine"
                stereotype("BehaviorSpec") {
                    extends(UmlMetaclass.StateMachine)
                    property<String>("spec") { default = "unknown" }
                }
                stereotype("A") { extends(UmlMetaclass.StateMachine) }
                stereotype("B") { extends(UmlMetaclass.StateMachine) }
                stereotype("New") { extends(UmlMetaclass.StateMachine) }
            }

        test(name = "stateMachine accepts stereotype on root scope") {
            val d =
                stateDiagram("OrderProcessing") {
                    applyProfile(testProfile)
                    stereotype("BehaviorSpec")
                    state("Idle")
                }
            val sm = d.elements.single() as UmlStateMachine
            sm.appliedStereotypes shouldHaveSize 1
            sm.appliedStereotypes.first().stereotypeName shouldBe "BehaviorSpec"
        }

        test(name = "stateMachine stereotype with tagged values") {
            val d =
                stateDiagram("OrderProcessing") {
                    applyProfile(testProfile)
                    stereotype("BehaviorSpec") {
                        "spec" to "RT-AUTOSAR-1.7"
                    }
                }
            val sm = d.elements.single() as UmlStateMachine
            sm.appliedStereotypes shouldHaveSize 1
            val app = sm.appliedStereotypes.first()
            app.stereotypeName shouldBe "BehaviorSpec"
            val specTag = app.tags["spec"]
            (specTag is TagValue.StringVal && specTag.v == "RT-AUTOSAR-1.7") shouldBe true
        }

        test(name = "multiple stereotypes on stateMachine accumulate") {
            val d =
                stateDiagram("X") {
                    applyProfile(testProfile)
                    stereotype("A")
                    stereotype("B")
                }
            val sm = d.elements.single() as UmlStateMachine
            sm.appliedStereotypes shouldHaveSize 2
            sm.appliedStereotypes.map { it.stereotypeName } shouldBe listOf("A", "B")
        }

        test(name = "stateMachine without stereotype has empty appliedStereotypes") {
            val d = stateDiagram("X") { state("S") }
            val sm = d.elements.single() as UmlStateMachine
            sm.appliedStereotypes.shouldBeEmpty()
        }

        test(name = "legacy stateMachineStereotypes coexist with applied stereotypes") {
            val d =
                stateDiagram("X") {
                    applyProfile(testProfile)
                    stateMachineStereotypes.add("legacy")
                    stereotype("New")
                }
            val sm = d.elements.single() as UmlStateMachine
            sm.stereotypes shouldBe listOf("legacy")
            sm.appliedStereotypes shouldHaveSize 1
            sm.appliedStereotypes.first().stereotypeName shouldBe "New"
        }

        test(name = "metaclass on StateDiagramBuilder is StateMachine") {
            val builder = StateDiagramBuilder("X")
            (builder as UmlElementScope).metaclass shouldBe UmlMetaclass.StateMachine
        }
    })
