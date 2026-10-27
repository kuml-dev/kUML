package dev.kuml.layout.bridge

import dev.kuml.layout.NodeId
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Sysml2LayoutBridgeNotesTest :
    FunSpec(body = {

        test("bdd graph contains note nodes (at least default size) and one edge per visible link") {
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
            val n = g.nodes.single { it.id == NodeId("n") }
            (n.intrinsicSize.width >= NoteLayout.DEFAULT_W) shouldBe true
            g.nodes.any { it.id == NodeId("f") } shouldBe true
            // link to invisible B is dropped
            g.edges.map { it.id.value } shouldBe listOf("note-link:n::A")
        }

        test("stm graph carries notes") {
            val model =
                sysml2Model(name = "M") {
                    val r = stateDef(name = "Red")
                    comment(text = "x", firstAnchor = r, id = "n")
                    stmDiagram(name = "S") { include(r) }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = model.diagrams.single() as StmDiagram)
            g.nodes.any { it.id == NodeId("n") } shouldBe true
            g.edges.size shouldBe 1
        }

        test("ibd graph carries notes anchored to part usages") {
            val model =
                sysml2Model(name = "M") {
                    val e = partDef(name = "Engine")
                    val v = partDef(name = "Vehicle") { part(name = "engine", typeId = e.id) }
                    comment(text = "x", "Vehicle::engine", id = "n")
                    ibd(name = "I", owner = v)
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = model.diagrams.single() as IbdDiagram)
            g.nodes.any { it.id == NodeId("n") } shouldBe true
            g.edges.size shouldBe 1
        }
    })
