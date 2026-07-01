package dev.kuml.sysml2.edge

import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * V2.0.13 — verifies the `trigger [guard] / effect` label format
 * across every null-combination, and that transitions whose endpoints
 * are outside the visible state set are dropped.
 */
class StmEdgeAdapterTest :
    StringSpec({

        fun model(vararg transitions: TransitionUsage): Sysml2Model =
            Sysml2Model(
                name = "Engine",
                definitions =
                    listOf(
                        StateDefinition(id = "Off", name = "Off"),
                        StateDefinition(id = "On", name = "On"),
                        StateDefinition(id = "Ghost", name = "Ghost"),
                    ),
                usages = transitions.toList(),
            )

        val diagram = StmDiagram(name = "Engine", elementIds = listOf("Off", "On"))

        "full label: trigger [guard] / effect" {
            val t =
                TransitionUsage(
                    id = "transition:Off::On",
                    name = "t1",
                    sourceStateId = "Off",
                    targetStateId = "On",
                    trigger = "powerOn",
                    guard = "battery > 0",
                    effect = "switchLights('green')",
                )
            val adapter = StmEdgeAdapter(model(t), diagram)
            val meta = adapter.metadataFor("transition:Off::On")!!
            meta.label shouldBe "powerOn [battery > 0] / switchLights('green')"
            meta.dashArray.shouldBeNull()
            meta.stereotype.shouldBeNull()
            meta.arrowHead shouldBe Sysml2ArrowHead.FilledTriangle
        }

        "trigger only — no brackets, no slash" {
            val t = TransitionUsage(id = "t", name = "t", sourceStateId = "Off", targetStateId = "On", trigger = "tick")
            val meta = StmEdgeAdapter(model(t), diagram).metadataFor("t")!!
            meta.label shouldBe "tick"
        }

        "guard only — square brackets only" {
            val t = TransitionUsage(id = "t", name = "t", sourceStateId = "Off", targetStateId = "On", guard = "ready")
            val meta = StmEdgeAdapter(model(t), diagram).metadataFor("t")!!
            meta.label shouldBe "[ready]"
        }

        "effect only — slash prefix only" {
            val t = TransitionUsage(id = "t", name = "t", sourceStateId = "Off", targetStateId = "On", effect = "halt()")
            val meta = StmEdgeAdapter(model(t), diagram).metadataFor("t")!!
            meta.label shouldBe "/ halt()"
        }

        "all three slots null — label is null, bare arrow renders" {
            val t = TransitionUsage(id = "t", name = "t", sourceStateId = "Off", targetStateId = "On")
            val meta = StmEdgeAdapter(model(t), diagram).metadataFor("t")!!
            meta.label.shouldBeNull()
        }

        "transition whose target state is invisible is dropped" {
            val t = TransitionUsage(id = "ghost", name = "g", sourceStateId = "Off", targetStateId = "Ghost")
            StmEdgeAdapter(model(t), diagram).metadataFor("ghost").shouldBeNull()
        }
    })
