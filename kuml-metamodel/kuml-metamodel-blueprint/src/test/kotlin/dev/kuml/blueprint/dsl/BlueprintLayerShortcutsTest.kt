package dev.kuml.blueprint.dsl

import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintLayer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Tests for the V3.1.24 layer-shortcut convenience builders
 * (`frontstage` / `backstage` / `support`).
 */
class BlueprintLayerShortcutsTest :
    StringSpec({
        "frontstage shortcut sets the FRONTSTAGE layer" {
            val m = blueprint("X") { phase("P") { frontstage("F") } }
            m.steps.single().layer shouldBe BlueprintLayer.FRONTSTAGE
        }

        "backstage shortcut sets the BACKSTAGE layer" {
            val m = blueprint("X") { phase("P") { backstage("B") } }
            m.steps.single().layer shouldBe BlueprintLayer.BACKSTAGE
        }

        "support shortcut sets the SUPPORT_PROCESSES layer" {
            val m = blueprint("X") { phase("P") { support("S") } }
            m.steps.single().layer shouldBe BlueprintLayer.SUPPORT_PROCESSES
        }

        "all four layers can coexist in one phase" {
            val m =
                blueprint("X") {
                    phase("P") {
                        customer("C", dev.kuml.blueprint.model.Sentiment.NEUTRAL)
                        frontstage("F")
                        backstage("B")
                        support("S")
                    }
                }
            m.steps.map { it.layer } shouldContainExactly
                listOf(
                    BlueprintLayer.CUSTOMER_ACTIONS,
                    BlueprintLayer.FRONTSTAGE,
                    BlueprintLayer.BACKSTAGE,
                    BlueprintLayer.SUPPORT_PROCESSES,
                )
            m.activeLayers() shouldBe BlueprintLayer.entries.toSet()
        }

        "shortcuts carry the actor reference" {
            val m =
                blueprint("X") {
                    val staff = actor("Staff", ActorRole.STAFF)
                    phase("P") { backstage("B", actor = staff) }
                }
            m.steps.single().actorRef shouldBe m.actors.single().id
        }
    })
