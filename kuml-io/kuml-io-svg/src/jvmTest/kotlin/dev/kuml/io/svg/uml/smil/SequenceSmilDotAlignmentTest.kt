package dev.kuml.io.svg.uml.smil

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
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.InteractionOperator
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlInteractionOperand
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Regression: the animated message dot must travel exactly along the painted arrow.
 *
 * The static renderer adds a 24 px header band before every combined-fragment operand
 * (`umlSeqRowOffset`) and raises the canvas padding when fragments are present. The animation
 * builder used to ignore both, so the dot drifted a further 24 px away from its arrow after every
 * operand and sat a constant 12 px off everywhere.
 */
class SequenceSmilDotAlignmentTest :
    FunSpec({

        val customer = UmlLifeline(id = "customer", name = "Customer", isActor = true)
        val frontend = UmlLifeline(id = "frontend", name = "Frontend")
        val backend = UmlLifeline(id = "backend", name = "Backend")
        val db = UmlLifeline(id = "db", name = "OrderDB")

        fun msg(
            seq: Int,
            from: UmlLifeline,
            to: UmlLifeline,
            sort: MessageSort = MessageSort.SYNC_CALL,
        ) = UmlMessage(
            id = "m$seq",
            label = "msg$seq",
            fromLifelineId = from.id,
            toLifelineId = to.id,
            sort = sort,
            sequence = seq,
        )

        // 1 Customer -> Frontend, 2 Frontend -> Backend,
        // alt [valid]:   3 Backend -> db, 4 db -> Backend, 5 Backend -> Frontend
        // alt [invalid]: 6 Backend -> Frontend
        // 7 Frontend -> Customer
        val messages =
            listOf(
                msg(seq = 1, from = customer, to = frontend),
                msg(seq = 2, from = frontend, to = backend),
                msg(seq = 3, from = backend, to = db),
                msg(seq = 4, from = db, to = backend, sort = MessageSort.REPLY),
                msg(seq = 5, from = backend, to = frontend, sort = MessageSort.REPLY),
                msg(seq = 6, from = backend, to = frontend, sort = MessageSort.REPLY),
                msg(seq = 7, from = frontend, to = customer, sort = MessageSort.REPLY),
            )

        val alt =
            UmlCombinedFragment(
                id = "alt1",
                operator = InteractionOperator.ALT,
                operands =
                    listOf(
                        UmlInteractionOperand(guard = "[valid]", messageIds = listOf("m3", "m4", "m5")),
                        UmlInteractionOperand(guard = "[invalid]", messageIds = listOf("m6")),
                    ),
            )

        fun interaction(withFragment: Boolean) =
            UmlInteraction(
                id = "seq",
                name = "Place Order",
                lifelines = listOf(customer, frontend, backend, db),
                messages = messages,
                fragments = if (withFragment) listOf(alt) else emptyList(),
            )

        fun layout() =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 700f, height = 460f),
                nodes =
                    listOf(customer, frontend, backend, db)
                        .mapIndexed { i, ll ->
                            NodeId(ll.id) to
                                NodeLayout(
                                    bounds =
                                        Rect(
                                            origin = Point(x = 20f + i * 160f, y = 20f),
                                            size = Size(width = 120f, height = 400f),
                                        ),
                                )
                        }.toMap(),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun trace() =
            TraceFile(
                entries =
                    messages.mapIndexed { i, m ->
                        TraceEntry.MessageSent(
                            seqNo = (i + 1).toLong(),
                            timestamp = "1970-01-01T00:00:00Z",
                            messageId = m.id,
                            fromLifelineId = m.fromLifelineId,
                            toLifelineId = m.toLifelineId,
                        )
                    },
            )

        val num = """(-?[\d.]+)"""

        /** Painted arrow of a message: x1, y1, x2, y2 as emitted by the static renderer. */
        fun arrow(
            svg: String,
            id: String,
        ): List<Float> {
            val m =
                Regex("""<g id="$id">\s*<line x1="$num" y1="$num" x2="$num" y2="$num"""").find(svg)
                    ?: error("arrow of $id not found in SVG")
            return m.groupValues.drop(1).map { it.toFloat() }
        }

        /** Motion paths of the first pass over all messages, in trace order: x1, y1, x2, y2. */
        fun dotPaths(svg: String): List<List<Float>> =
            Regex("""<animateMotion[^>]*path="M $num,$num L $num,$num"""")
                .findAll(svg)
                .take(messages.size)
                .map { m -> m.groupValues.drop(1).map { it.toFloat() } }
                .toList()

        for (withFragment in listOf(false, true)) {
            test("dot follows the painted arrow of every message (fragments: $withFragment)") {
                val diagram =
                    KumlDiagram(
                        name = "Place Order",
                        type = DiagramType.SEQUENCE,
                        elements = listOf(interaction(withFragment = withFragment)),
                    )
                val theme = PlainTheme()
                val staticSvg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout(), theme = theme)
                val animated =
                    SequenceSmilRenderer.render(
                        diagram = diagram,
                        layoutResult = layout(),
                        theme = theme,
                        trace = trace(),
                    )
                animated.hasAnimation shouldBe true

                val paths = dotPaths(animated.svg)
                paths.size shouldBe messages.size
                messages.forEachIndexed { i, m ->
                    val painted = arrow(svg = staticSvg, id = m.id)
                    for (k in 0..3) {
                        paths[i][k] shouldBe (painted[k] plusOrMinus 0.01f)
                    }
                }
            }
        }
    })
