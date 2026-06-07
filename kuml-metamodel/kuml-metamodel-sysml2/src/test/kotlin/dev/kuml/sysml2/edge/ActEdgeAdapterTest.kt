package dev.kuml.sysml2.edge

import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * V2.0.13 — verifies that [ActEdgeAdapter] formats ControlFlow `[guard]`
 * and ObjectFlow `[objectType]` labels correctly and drops flows whose
 * endpoints aren't visible.
 */
class ActEdgeAdapterTest :
    StringSpec({

        val diagram = ActDiagram(name = "Order", elementIds = listOf("Start", "Validate", "Ship"))

        fun model(vararg flows: dev.kuml.sysml2.Sysml2Usage): Sysml2Model =
            Sysml2Model(
                name = "Order",
                definitions =
                    listOf(
                        ActionDefinition(id = "Start", name = "Start", kind = ActivityNodeKind.Initial),
                        ActionDefinition(id = "Validate", name = "Validate"),
                        ActionDefinition(id = "Ship", name = "Ship"),
                    ),
                usages = flows.toList(),
            )

        "ControlFlow with guard → [guard] label, solid, filled triangle" {
            val flow =
                ControlFlowUsage(
                    id = "controlFlow:Start::Validate",
                    name = "f",
                    sourceNodeId = "Start",
                    targetNodeId = "Validate",
                    guard = "ready",
                )
            val meta = ActEdgeAdapter(model(flow), diagram).metadataFor("controlFlow:Start::Validate")!!
            meta.label shouldBe "[ready]"
            meta.dashArray.shouldBeNull()
            meta.arrowHead shouldBe Sysml2ArrowHead.FilledTriangle
        }

        "ControlFlow without guard → null label, bare arrow" {
            val flow =
                ControlFlowUsage(
                    id = "cf",
                    name = "f",
                    sourceNodeId = "Validate",
                    targetNodeId = "Ship",
                )
            val meta = ActEdgeAdapter(model(flow), diagram).metadataFor("cf")!!
            meta.label.shouldBeNull()
        }

        "ObjectFlow with objectType → [Type] label" {
            val flow =
                ObjectFlowUsage(
                    id = "objectFlow:Validate::Ship",
                    name = "o",
                    sourceNodeId = "Validate",
                    targetNodeId = "Ship",
                    objectType = "Order",
                )
            val meta = ActEdgeAdapter(model(flow), diagram).metadataFor("objectFlow:Validate::Ship")!!
            meta.label shouldBe "[Order]"
            meta.arrowHead shouldBe Sysml2ArrowHead.FilledTriangle
        }

        "Flow whose target is invisible is dropped" {
            val flow =
                ControlFlowUsage(
                    id = "ghost",
                    name = "g",
                    sourceNodeId = "Start",
                    targetNodeId = "Ghost",
                )
            ActEdgeAdapter(model(flow), diagram).metadataFor("ghost").shouldBeNull()
        }
    })
