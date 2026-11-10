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
import dev.kuml.uml.InteractionOperator
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlInteractionOperand
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 63 (f): a blank operand guard of a UML sequence fragment draws no empty `[]`. */
class UmlSeqBlankGuardSvgTest :
    StringSpec({
        fun render(guard: String?): String {
            val a = UmlLifeline(id = "I::a", name = "Client")
            val b = UmlLifeline(id = "I::b", name = "Server")
            val m1 =
                UmlMessage(
                    id = "I::m1",
                    label = "one",
                    fromLifelineId = a.id,
                    toLifelineId = b.id,
                    sort = MessageSort.SYNC_CALL,
                    sequence = 1,
                )
            val m2 =
                UmlMessage(id = "I::m2", label = "two", fromLifelineId = b.id, toLifelineId = a.id, sort = MessageSort.REPLY, sequence = 2)
            val frag =
                UmlCombinedFragment(
                    id = "I::f",
                    operator = InteractionOperator.PAR,
                    operands =
                        listOf(
                            UmlInteractionOperand(guard = guard, messageIds = listOf(m1.id)),
                            UmlInteractionOperand(guard = guard, messageIds = listOf(m2.id)),
                        ),
                )
            val interaction =
                UmlInteraction(id = "I", name = "I", lifelines = listOf(a, b), messages = listOf(m1, m2), fragments = listOf(frag))
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
            return KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
        }

        "blank, empty and missing guards draw no empty brackets" {
            for (guard in listOf("", "   ", null)) {
                val svg = render(guard)
                svg shouldContain "PAR"
                svg shouldNotContain "[]"
            }
        }

        "a real guard is still drawn" {
            render("ok") shouldContain "[ok]"
        }
    })
