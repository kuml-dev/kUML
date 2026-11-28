package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NoteRoom
import dev.kuml.layout.bridge.IbdNoteRoomProbe
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.PlacementHintMiss
import dev.kuml.layout.bridge.note.UndrawnNote
import dev.kuml.layout.bridge.withNoteRooms
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

/** PAL Welle Y4: the gate that keeps the second IBD layout (with note room) only when it is strictly better. */
class IbdNoteRoomGateTest :
    FunSpec({
        fun miss(
            id: String,
            code: String? = null,
        ) = PlacementHintMiss(
            noteId = id,
            wanted = NotePlacement.ABOVE,
            placed = NotePlacement.NEAR,
            code = code,
        )

        fun run(
            misses: List<PlacementHintMiss>,
            width: Int,
            height: Int,
            undrawn: List<UndrawnNote> = emptyList(),
        ) = KumlSvgRenderer.IbdRun(
            svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height"></svg>""",
            misses = misses,
            undrawn = undrawn,
        )

        fun engineLayout(
            model: Sysml2Model,
            diagram: IbdDiagram,
            rooms: Map<NodeId, NoteRoom> = emptyMap(),
        ): LayoutResult {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            return engine.layout(
                graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram).withNoteRooms(rooms),
                hints = LayoutHints.DEFAULT,
            )
        }

        fun model(withNote: Boolean): Pair<Sysml2Model, IbdDiagram> {
            val m =
                sysml2Model(name = "G") {
                    val t = partDef(name = "T")
                    val sys =
                        partDef(name = "Sys") {
                            part(name = "a", typeId = t.id)
                            part(name = "b", typeId = t.id)
                        }
                    if (withNote) comment(text = "above a", "Sys::a", id = "n1", placement = NotePlacement.ABOVE)
                    ibd(name = "Sys", owner = sys) {}
                }
            return m to m.diagrams.filterIsInstance<IbdDiagram>().single()
        }

        test("verdict: B is kept only when it has fewer note misses and nothing gets worse") {
            val a = run(misses = listOf(miss("n1")), width = 400, height = 300)
            KumlSvgRenderer.ibdGateVerdict(a = a, b = run(misses = emptyList(), width = 400, height = 300)) shouldBe null
            KumlSvgRenderer.ibdGateVerdict(a = a, b = run(misses = emptyList(), width = 380, height = 310)) shouldBe null
            KumlSvgRenderer.ibdGateVerdict(a = a, b = run(misses = listOf(miss("n1")), width = 300, height = 300)).shouldNotBeEmpty()
            KumlSvgRenderer.ibdGateVerdict(a = a, b = run(misses = emptyList(), width = 400, height = 301)).shouldNotBeEmpty()
        }

        test("verdict: more port label findings or a note that is no longer drawn keep A") {
            val a = run(misses = listOf(miss("n1")), width = 400, height = 300)
            val worsePort = run(misses = listOf(miss("p", PlacementHintMiss.PORT_LABEL_NO_FREE_POSITION)), width = 400, height = 300)
            KumlSvgRenderer.ibdGateVerdict(a = a, b = worsePort) shouldBe "more port labels without a free position"
            val worseHint = run(misses = listOf(miss("p", PlacementHintMiss.PORT_LABEL_HINT_NOT_HONOURED)), width = 400, height = 300)
            KumlSvgRenderer.ibdGateVerdict(a = a, b = worseHint) shouldBe "more port label hint misses"
            val lostNote =
                run(
                    misses = emptyList(),
                    width = 400,
                    height = 300,
                    undrawn = listOf(UndrawnNote(noteId = "x", anchorId = "y", reason = "r")),
                )
            KumlSvgRenderer.ibdGateVerdict(a = a, b = lostNote) shouldBe "more undrawn notes"
            // a port label finding A already had does not count against B
            val aWithPort =
                run(misses = listOf(miss("n1"), miss("p", PlacementHintMiss.PORT_LABEL_NO_FREE_POSITION)), width = 400, height = 300)
            KumlSvgRenderer.ibdGateVerdict(a = aWithPort, b = worsePort) shouldBe null
        }

        test("without a note that could reserve room the second layout is never asked for and the SVG is the same") {
            val (m, d) = model(withNote = false)
            val layout = engineLayout(m, d)
            var calls = 0
            val plain = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layout, theme = PlainTheme())
            val gated =
                KumlSvgRenderer.toSvg(
                    model = m,
                    diagram = d,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    relayout = {
                        calls++
                        layout
                    },
                )
            calls shouldBe 0
            gated shouldBe plain
        }

        test("a missed ABOVE note makes the gate ask for room; B is kept when it honours the note on a smaller drawing") {
            val (m, d) = model(withNote = true)
            val layout = engineLayout(m, d)
            var asked: Map<NodeId, NoteRoom>? = null
            val relayout: (Map<NodeId, NoteRoom>) -> LayoutResult = {
                asked = it
                engineLayout(m, d, it)
            }
            val plain = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layout, theme = PlainTheme())
            val (rendered, decisions) =
                IbdNoteRoomProbe.collecting {
                    NotePlacementMissCollector.collecting {
                        KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layout, theme = PlainTheme(), relayout = relayout)
                    }
                }
            val (gated, misses) = rendered
            asked!!.keys shouldBe setOf(NodeId("Sys::a"))
            val decision = decisions.single()
            decision.reservedNoteIds shouldBe listOf("n1")
            decision.missesA shouldBe 1
            decision.missesB shouldBe 0
            decision.takenB shouldBe true
            (decision.areaB <= decision.areaA) shouldBe true
            // only the findings of the kept run: B honours the note, so no W004 reaches the outer scope
            misses.isEmpty() shouldBe true
            (gated == plain) shouldBe false
        }

        test("A is kept word for word, and its findings reported once, when the second layout is no better") {
            val (m, d) = model(withNote = true)
            val layout = engineLayout(m, d)
            val plain = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layout, theme = PlainTheme())
            val (plainMisses, _) =
                NotePlacementMissCollector
                    .collecting {
                        KumlSvgRenderer.toSvg(
                            model = m,
                            diagram = d,
                            layoutResult = layout,
                            theme = PlainTheme(),
                        )
                    }.let {
                        it.second to
                            it.first
                    }
            // a second layout without any room (as if ELK ignored it) cannot gain a note: the gate keeps A
            val (rendered, decisions) =
                IbdNoteRoomProbe.collecting {
                    NotePlacementMissCollector.collecting {
                        KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layout, theme = PlainTheme(), relayout = { layout })
                    }
                }
            decisions.single().takenB shouldBe false
            rendered.first shouldBe plain
            rendered.second.map { it.message() } shouldBe plainMisses.map { it.message() }
            rendered.second.size shouldBe 1
        }

        test("switched off, the gate never asks for a second layout") {
            val (m, d) = model(withNote = true)
            val layout = engineLayout(m, d)
            var calls = 0
            val plain = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layout, theme = PlainTheme())
            val (off, decisions) =
                IbdNoteRoomProbe.collecting(gate = false) {
                    KumlSvgRenderer.toSvg(
                        model = m,
                        diagram = d,
                        layoutResult = layout,
                        theme = PlainTheme(),
                        relayout = {
                            calls++
                            layout
                        },
                    )
                }
            calls shouldBe 0
            decisions.isEmpty() shouldBe true
            off shouldBe plain
        }

        test("the gate is deterministic: the same input renders to the same bytes twice") {
            val (m, d) = model(withNote = true)
            val layout = engineLayout(m, d)
            val one =
                KumlSvgRenderer.toSvg(
                    model = m,
                    diagram = d,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    relayout = { engineLayout(m, d, it) },
                )
            val two =
                KumlSvgRenderer.toSvg(
                    model = m,
                    diagram = d,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    relayout = { engineLayout(m, d, it) },
                )
            one shouldBe two
        }
    })
