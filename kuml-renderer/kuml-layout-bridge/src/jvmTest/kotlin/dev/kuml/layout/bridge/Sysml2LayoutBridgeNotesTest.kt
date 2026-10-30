package dev.kuml.layout.bridge

import dev.kuml.layout.NodeId
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Notes are no longer part of the ELK graph: ELK knows no edge labels, port labels or composite titles, so
 * [dev.kuml.layout.bridge.note.Sysml2NotePlacement] places them after the layout.
 */
class Sysml2LayoutBridgeNotesTest :
    FunSpec(body = {

        test("bdd graph contains neither note nodes nor note edges") {
            val model =
                sysml2Model(name = "M") {
                    val a = partDef(name = "A")
                    val b = partDef(name = "B")
                    comment(text = "two", firstAnchor = a, b, id = "n")
                    bdd(name = "D") {
                        include(a)
                        comment(text = "free", id = "f")
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = model.diagrams.single() as BdDiagram)
            g.nodes.map { it.id } shouldBe listOf(NodeId("A"))
            g.edges.size shouldBe 0
        }

        test("stm graph carries no notes") {
            val model =
                sysml2Model(name = "M") {
                    val r = stateDef(name = "Red")
                    comment(text = "x", firstAnchor = r, id = "n")
                    stmDiagram(name = "S") { include(r) }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = model.diagrams.single() as StmDiagram)
            g.nodes.any { it.id == NodeId("n") } shouldBe false
            g.edges.size shouldBe 0
        }

        test("ibd graph carries no notes") {
            val model =
                sysml2Model(name = "M") {
                    val e = partDef(name = "Engine")
                    val v = partDef(name = "Vehicle") { part(name = "engine", typeId = e.id) }
                    comment(text = "x", "Vehicle::engine", id = "n")
                    ibd(name = "I", owner = v)
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = model.diagrams.single() as IbdDiagram)
            g.nodes.any { it.id == NodeId("n") } shouldBe false
            g.edges.size shouldBe 0
        }
    })
