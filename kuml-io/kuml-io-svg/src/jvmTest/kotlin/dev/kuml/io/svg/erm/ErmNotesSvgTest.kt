package dev.kuml.io.svg.erm

import dev.kuml.core.model.note.NotePlacement
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
import dev.kuml.layout.bridge.note.ErmNotePlacement
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** End-to-end (real ELK) tests: ERM notes in all four notations. */
class ErmNotesSvgTest :
    FunSpec(body = {

        fun model(
            notation: ErmNotation,
            hint: NotePlacement? = null,
        ): ErmModel =
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
                note(text = "Anchored <b>note</b> & \"x\"", c, v, id = "n1", placement = hint)
                note(text = "free-standing", id = "free")
                diagram(name = "Shop", notation = notation)
            }

        fun render(
            notation: ErmNotation,
            hint: NotePlacement? = null,
        ): Pair<LayoutResult, String> {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            val m = model(notation, hint)
            val d = m.diagrams.single()
            val graph =
                when (notation) {
                    ErmNotation.CHEN -> ErmChenLayoutBridge.toChenLayoutGraph(model = m, diagram = d)
                    ErmNotation.IDEF1X -> ErmIdef1xLayoutBridge.toLayoutGraph(model = m, diagram = d)
                    else -> ErmLayoutBridge.toLayoutGraph(model = m, diagram = d)
                }
            val lr = engine.layout(graph = graph, hints = LayoutHints.DEFAULT)
            val svg = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = lr, theme = PlainTheme())
            if (hint == null) SampleOutput.write(filename = "erm/notes-${notation.name.lowercase()}.svg", content = svg)
            return lr to svg
        }

        for (notation in ErmNotation.entries) {
            test("$notation: notes render with a fold and dashed anchors") {
                val (lr, svg) = render(notation)
                val size = lr.nodes[NodeId("n1")]!!.bounds.size
                (size.width >= NoteLayout.DEFAULT_W && size.height >= NoteLayout.MIN_H) shouldBe true
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

        for (notation in ErmNotation.entries) {
            test("$notation: no hint and NEAR render byte-identically, a side hint keeps note and link drawn") {
                val (_, plain) = render(notation)
                val (_, near) = render(notation, NotePlacement.NEAR)
                near shouldBe plain
                for (side in listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                    val (lr, svg) = render(notation, side)
                    svg shouldContain "id=\"n1\""
                    svg shouldContain "class=\"kuml-note-anchor\""
                    lr.edges.keys.count { it.value.startsWith("note_link_") } shouldBe 2
                }
            }
        }

        for (notation in ErmNotation.entries) {
            test("$notation: RIGHT and ABOVE hints put the placed note beside and above its entity, and change the SVG") {
                val (_, plain) = render(notation)
                val svgs = mutableSetOf(plain)
                for (side in listOf(NotePlacement.RIGHT, NotePlacement.ABOVE)) {
                    val (lr, svg) = render(notation, side)
                    svgs += svg
                    val m = model(notation, side)
                    val resolve =
                        if (notation == ErmNotation.CHEN) ErmNotePlacement.chenTarget(layout = lr) else { id: String -> id }
                    val placed = ErmNotePlacement.place(model = m, layout = lr, resolveTarget = resolve).layout
                    val anchorId = m.entities.first().id
                    val anchorKey = lr.nodes.keys.first { it.value == anchorId || it.value.endsWith("::$anchorId") }
                    val a = placed.nodes.getValue(anchorKey).bounds
                    val n = placed.nodes.getValue(NodeId("n1")).bounds
                    when (side) {
                        NotePlacement.RIGHT -> (n.origin.x >= a.origin.x + a.size.width - 0.5f) shouldBe true
                        else -> (n.origin.y + n.size.height <= a.origin.y + 0.5f) shouldBe true
                    }
                }
                svgs.size shouldBe 3
            }
        }
    })
