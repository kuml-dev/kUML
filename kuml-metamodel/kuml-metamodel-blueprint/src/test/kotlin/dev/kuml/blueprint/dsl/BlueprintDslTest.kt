package dev.kuml.blueprint.dsl

import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.ConnectionStyle
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.blueprint.model.TouchpointSymbol
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * DSL tests for V3.1.22: builder output equivalence, deterministic auto-ids,
 * phase ordering, infix flowsTo, diagram defaults.
 */
class BlueprintDslTest :
    StringSpec({
        "blueprint builder produces expected model" {
            val m =
                blueprint("Onboarding") {
                    val web = channel("Website", ChannelKind.WEB)
                    val kunde = actor("Kunde", ActorRole.CUSTOMER)
                    val banner = touchpoint("Banner", channel = web, symbol = TouchpointSymbol.CIRCLE)
                    phase("Entdeckung") {
                        customer(
                            "Sieht Anzeige",
                            Sentiment.NEUTRAL,
                            touchpoints = listOf(banner),
                            actor = kunde,
                        )
                    }
                    journeyDiagram("J")
                }
            m.name shouldBe "Onboarding"
            m.channels.single().id shouldBe "channel_0"
            m.actors.single().id shouldBe "actor_0"
            m.phases.single().id shouldBe "phase_0"
            m.phases.single().order shouldBe 0
            m.touchpoints.single().id shouldBe "tp_0"
            m.steps.single().id shouldBe "step_0"
            m.steps.single().sentiment shouldBe Sentiment.NEUTRAL
            m.steps.single().layer shouldBe BlueprintLayer.CUSTOMER_ACTIONS
            m.steps.single().touchpointRefs shouldBe listOf("tp_0")
            (m.diagrams.single() as JourneyDiagram).name shouldBe "J"
        }

        "auto-ids are deterministic and collision-free" {
            val m =
                blueprint("X") {
                    channel("a")
                    channel("b")
                    phase("P0") {
                        step("s")
                        step("s2")
                    }
                    phase("P1") { step("s3") }
                }
            m.channels.map { it.id } shouldBe listOf("channel_0", "channel_1")
            m.phases.map { it.id } shouldBe listOf("phase_0", "phase_1")
            m.steps.map { it.id } shouldBe listOf("step_0", "step_1", "step_2")
        }

        "phase declaration order produces gap-free order values" {
            val m =
                blueprint("X") {
                    phase("A") {}
                    phase("B") {}
                    phase("C") {}
                }
            m.phases.map { it.order } shouldBe listOf(0, 1, 2)
            m.phases.map { it.name } shouldBe listOf("A", "B", "C")
        }

        "customer convenience sets layer and sentiment" {
            val m =
                blueprint("X") {
                    phase("P") { customer("c", Sentiment.VERY_POSITIVE) }
                }
            val s = m.steps.single()
            s.layer shouldBe BlueprintLayer.CUSTOMER_ACTIONS
            s.sentiment shouldBe Sentiment.VERY_POSITIVE
        }

        "generic step can target any layer" {
            val m =
                blueprint("X") {
                    phase("P") { step("b", BlueprintLayer.BACKSTAGE) }
                }
            m.steps.single().layer shouldBe BlueprintLayer.BACKSTAGE
        }

        "flowsTo infix wires a connection" {
            val m =
                blueprint("X") {
                    lateinit var a: String
                    lateinit var b: String
                    phase("P") {
                        a = step("a")
                        b = step("b", BlueprintLayer.BACKSTAGE)
                    }
                    a flowsTo b
                }
            m.connections.single().sourceRef shouldBe "step_0"
            m.connections.single().targetRef shouldBe "step_1"
            m.connections.single().style shouldBe ConnectionStyle.SOLID
        }

        "explicit connection with dashed style" {
            val m =
                blueprint("X") {
                    lateinit var a: String
                    lateinit var b: String
                    phase("P") {
                        a = step("a")
                        b = step("b")
                    }
                    connection(a, b, ConnectionStyle.DASHED)
                }
            m.connections.single().style shouldBe ConnectionStyle.DASHED
        }

        "journeyDiagram vs blueprintDiagram defaults" {
            val m =
                blueprint("X") {
                    phase("P") { customer("c", Sentiment.NEUTRAL) }
                    journeyDiagram("J")
                    blueprintDiagram("B")
                }
            val j = m.diagrams.filterIsInstance<JourneyDiagram>().single()
            j.visibleLayers shouldBe setOf(BlueprintLayer.CUSTOMER_ACTIONS)
            j.showEmotionCurve shouldBe true
            val b = m.diagrams.filterIsInstance<BlueprintDiagramFull>().single()
            b.visibleLayers shouldBe BlueprintLayer.entries.toSet()
            b.showEmotionCurve shouldBe false
        }
    })
