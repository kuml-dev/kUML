package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 66 (c4): lifeline head lines of a UML sequence diagram. */
class UmlSeqHeadWrapSvgTest :
    StringSpec({
        fun render(name: String): String {
            val a = UmlLifeline(id = "I::a", name = name)
            val b = UmlLifeline(id = "I::b", name = "Server")
            val m =
                UmlMessage(
                    id = "I::m",
                    label = "one",
                    fromLifelineId = a.id,
                    toLifelineId = b.id,
                    sort = MessageSort.SYNC_CALL,
                    sequence = 1,
                )
            val interaction = UmlInteraction(id = "I", name = "I", lifelines = listOf(a, b), messages = listOf(m))
            val diagram = KumlDiagram(name = "I", type = DiagramType.SEQUENCE, elements = listOf(interaction))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 400f),
                    nodes =
                        mapOf(
                            NodeId(a.id) to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 140f, height = 300f))),
                            NodeId(b.id) to
                                NodeLayout(bounds = Rect(origin = Point(x = 220f, y = 20f), size = Size(width = 140f, height = 300f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            return KumlSvgRenderer
                .toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
                .replace(Regex(">\\s+"), ">")
                .replace(Regex("\\s+<"), "<")
        }

        "a newline in a UML lifeline name is a line break and the keyword is dropped" {
            val svg = render("First\nSecond")
            svg shouldContain ">First</text>"
            svg shouldContain ">Second</text>"
            svg shouldNotContain "FirstSecond"
            svg shouldNotContain "«lifeline»"
            svg shouldContain "<title>First Second</title>"
        }

        "short UML names keep the keyword and add no title" {
            val svg = render("Client")
            svg shouldContain "«lifeline»"
            (svg.contains("<title>")) shouldBe false
        }
    })
