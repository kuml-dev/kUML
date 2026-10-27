package dev.kuml.io.svg.erm

import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.erm.ErmChenLayoutBridge
import dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge
import dev.kuml.layout.bridge.erm.ErmLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** End-to-end (real ELK) tests: ERM notes in all four notations. */
class ErmNotesSvgTest :
    FunSpec(body = {

        fun model(notation: ErmNotation): ErmModel =
            ermModel(name = "Shop") {
                val c =
                    entity(name = "Customer") {
                        id(name = "id", type = ErmDataType.Integer(64))
                    }
                val o =
                    entity(name = "Order") {
                        id(name = "id", type = ErmDataType.Integer(64))
                    }
                c oneToMany o
                val v = view(name = "OpenOrders", query = "select 1", references = listOf(o))
                note(text = "Anchored <b>note</b> & \"x\"", c, v, id = "n1")
                note(text = "free-standing", id = "free")
                diagram(name = "Shop", notation = notation)
            }

        fun render(notation: ErmNotation): Pair<LayoutResult, String> {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            val m = model(notation)
            val d = m.diagrams.single()
            val graph =
                when (notation) {
                    ErmNotation.CHEN -> ErmChenLayoutBridge.toChenLayoutGraph(model = m, diagram = d)
                    ErmNotation.IDEF1X -> ErmIdef1xLayoutBridge.toLayoutGraph(model = m, diagram = d)
                    else -> ErmLayoutBridge.toLayoutGraph(model = m, diagram = d)
                }
            val lr = engine.layout(graph = graph, hints = LayoutHints.DEFAULT)
            val svg = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = lr, theme = PlainTheme())
            SampleOutput.write(filename = "erm/notes-${notation.name.lowercase()}.svg", content = svg)
            return lr to svg
        }

        for (notation in ErmNotation.entries) {
            test("$notation: notes render with a fold and dashed anchors") {
                val (lr, svg) = render(notation)
                val size = lr.nodes[NodeId("n1")]!!.bounds.size
                (size.width >= NoteLayout.DEFAULT_W && size.height >= NoteLayout.DEFAULT_H) shouldBe true
                svg shouldContain "class=\"kuml-comment kuml-erm-note\""
                svg shouldContain "id=\"n1\""
                svg shouldContain "id=\"free\""
                svg shouldContain "class=\"kuml-note-anchor\""
                svg shouldContain "&lt;b&gt;note&lt;/b&gt;"
                svg shouldNotContain "<b>note"
                // a link per anchor
                lr.edges.keys.count { it.value.startsWith("note_link_") } shouldBe 2
            }
        }
    })
