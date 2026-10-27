package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.placeSeqNotes
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** End-to-end (real ELK) tests: SysML 2 comments in all eight diagram kinds. */
class Sysml2NotesSvgTest :
    FunSpec(body = {

        fun layout(
            model: Sysml2Model,
            diagram: Sysml2Diagram,
        ): LayoutResult {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            val graph =
                when (diagram) {
                    is BdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is IbdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is UcDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is ReqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is StmDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is ActDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is SeqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is ParDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                }
            return engine.layout(graph = graph, hints = LayoutHints.DEFAULT)
        }

        fun render(
            model: Sysml2Model,
            diagram: Sysml2Diagram,
            sample: String,
        ): String {
            val lr = layout(model, diagram)
            val svg =
                when (diagram) {
                    is BdDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is IbdDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is UcDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is ReqDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is StmDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is ActDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is SeqDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is ParDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                }
            SampleOutput.write(filename = "sysml2/notes-$sample.svg", content = svg)
            return svg
        }

        fun assertNotes(svg: String) {
            svg shouldContain "class=\"kuml-comment kuml-sysml2-comment\""
            svg shouldContain "id=\"n1\""
            svg shouldContain "class=\"kuml-note-anchor\""
            svg shouldContain "id=\"free\""
            svg shouldContain "Anchored note"
        }

        test("bdd: anchored note with two anchors and a free-standing note") {
            val model =
                sysml2Model(name = "M") {
                    val v = partDef(name = "Vehicle")
                    val e = partDef(name = "Engine", specializesId = v.id)
                    comment(text = "Anchored note", firstAnchor = v, e, id = "n1")
                    bdd(name = "BDD") {
                        include(v)
                        include(e)
                        comment(text = "Free-standing <b>note</b>", id = "free")
                    }
                }
            val bdd = model.diagrams.single() as BdDiagram
            val lr = layout(model, bdd)
            val n = lr.nodes[NodeId("n1")]!!
            (n.bounds.size.width >= NoteLayout.DEFAULT_W) shouldBe true
            (n.bounds.size.height >= NoteLayout.DEFAULT_H) shouldBe true
            val svg = render(model, bdd, "bdd")
            assertNotes(svg)
            svg shouldContain "&lt;b&gt;note&lt;/b&gt;"
            svg shouldNotContain "<b>note"
        }

        test("note anchored only to an invisible element is not drawn") {
            val model =
                sysml2Model(name = "M") {
                    val v = partDef(name = "Vehicle")
                    partDef(name = "Hidden")
                    comment(text = "ghost", "Hidden", id = "ghostNote")
                    bdd(name = "BDD") { include(v) }
                }
            val svg = render(model, model.diagrams.single(), "bdd-hidden")
            svg shouldNotContain "ghostNote"
        }

        test("ibd: note on a part usage; port enrichment ignores notes") {
            val model =
                sysml2Model(name = "M") {
                    val e = partDef(name = "Engine") { port(name = "out", typeId = "P") }
                    val b = partDef(name = "Battery") { port(name = "inp", typeId = "P") }
                    val v =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = e.id)
                            part(name = "battery", typeId = b.id)
                            connect(
                                name = "wire",
                                typeId = "C",
                                sourceEndId = "Vehicle::engine::out",
                                targetEndId = "Vehicle::battery::inp",
                            )
                        }
                    comment(text = "Anchored note", "Vehicle::engine", id = "n1")
                    comment(text = "free", id = "free")
                    ibd(name = "IBD", owner = v) { includeCommentById("free") }
                }
            assertNotes(render(model, model.diagrams.single(), "ibd"))
        }

        test("uc: notes") {
            val model =
                sysml2Model(name = "M") {
                    val a = actorDef(name = "Reader")
                    val u = useCaseDef(name = "Borrow")
                    comment(text = "Anchored note", firstAnchor = a, id = "n1")
                    ucDiagram(name = "UC") {
                        include(a)
                        include(u)
                        association(actor = a, useCase = u)
                        comment(text = "free", id = "free")
                    }
                }
            assertNotes(render(model, model.diagrams.single(), "uc"))
        }

        test("req: notes") {
            val model =
                sysml2Model(name = "M") {
                    val r = requirementDef(name = "R1", reqId = "R-1", text = "The system shall work")
                    comment(text = "Anchored note", firstAnchor = r, id = "n1")
                    reqDiagram(name = "REQ") {
                        include(r)
                        comment(text = "free", id = "free")
                    }
                }
            assertNotes(render(model, model.diagrams.single(), "req"))
        }

        test("stm: notes route through ELK next to transitions") {
            val model =
                sysml2Model(name = "M") {
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    transition(name = "go", source = red, target = green)
                    comment(text = "Anchored note", firstAnchor = red, id = "n1")
                    stmDiagram(name = "STM") {
                        include(red)
                        include(green)
                        comment(text = "free", id = "free")
                    }
                }
            assertNotes(render(model, model.diagrams.single(), "stm"))
        }

        test("act: notes with an action inside no partition") {
            val model =
                sysml2Model(name = "M") {
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    controlFlow(name = "f", source = a, target = b)
                    comment(text = "Anchored note", firstAnchor = a, id = "n1")
                    actDiagram(name = "ACT") {
                        include(a)
                        include(b)
                        comment(text = "free", id = "free")
                    }
                }
            assertNotes(render(model, model.diagrams.single(), "act"))
        }

        test("par: notes") {
            val model =
                sysml2Model(name = "M") {
                    val c = constraintDef(name = "C1", expression = "a == b")
                    comment(text = "Anchored note", firstAnchor = c, id = "n1")
                    parDiagram(name = "PAR") {
                        include(c)
                        comment(text = "free", id = "free")
                    }
                }
            assertNotes(render(model, model.diagrams.single(), "par"))
        }

        test("seq: notes are placed right of the rightmost lifeline and linked horizontally") {
            val model =
                sysml2Model(name = "M") {
                    val a = lifelineDef(name = "A")
                    val b = lifelineDef(name = "B")
                    message(label = "ping", source = a, target = b, seqNo = 0)
                    comment(text = "Anchored note", firstAnchor = b, id = "n1")
                    seqDiagram(name = "SEQ") {
                        include(a)
                        include(b)
                        comment(text = "free", id = "free")
                    }
                }
            val seq = model.diagrams.single() as SeqDiagram
            val raw = layout(model, seq)
            val placed = Sysml2LayoutBridge.placeSeqNotes(model = model, diagram = seq, layoutResult = raw)
            val rightmost = raw.nodes.values.maxOf { it.bounds.origin.x + it.bounds.size.width }
            val note = placed.nodes[NodeId("n1")]!!
            (note.bounds.origin.x > rightmost) shouldBe true
            (placed.canvas.width > raw.canvas.width) shouldBe true
            placed.edges.keys.any { it.value.startsWith("note-link:n1::") } shouldBe true
            // no overlap with the other note
            val free = placed.nodes[NodeId("free")]!!
            (free.bounds.origin.y >= note.bounds.origin.y + note.bounds.size.height) shouldBe true
            assertNotes(render(model, seq, "seq"))
        }
    })
