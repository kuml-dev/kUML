@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private val a = rect(x = 400f, y = 300f, w = 120f, h = 60f)
private val elkNote = rect(x = 600f, y = 300f, w = 100f, h = 40f)

private fun layoutOf(extra: Map<String, Rect> = emptyMap()) =
    LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = null,
        canvas = Size(width = 900f, height = 700f),
        nodes =
            (mapOf("a" to a, "n1" to elkNote) + extra).entries.associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
        edges = mapOf(EdgeId("l1") to EdgeRoute.Direct(source = Point(x = 600f, y = 320f), target = Point(x = 520f, y = 330f))),
        groups = emptyMap(),
    )

private fun diagramWith(
    placement: NotePlacement?,
    anchor: String = "a",
    type: DiagramType = DiagramType.CLASS,
) = KumlDiagram(
    id = "d",
    name = "D",
    type = type,
    elements =
        listOf(
            UmlClass(id = "a", name = "A"),
            UmlComment(id = "n1", body = "Note text", placement = placement),
            UmlCommentLink(id = "l1", commentId = "n1", annotatedElementId = anchor),
        ),
)

class UmlNotePlacementHintTest :
    FunSpec({
        fun run(
            placement: NotePlacement?,
            layout: LayoutResult = layoutOf(),
            diagram: KumlDiagram = diagramWith(placement),
        ) = NotePlacementMissCollector.collecting { UmlNotePlacement.place(diagram = diagram, layout = layout) }

        test("each side hint puts the note on that side of its anchor and reports nothing") {
            for (side in listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val (res, misses) = run(side)
                val n =
                    res.layout.nodes
                        .getValue(NodeId("n1"))
                        .bounds
                misses.shouldBeEmpty()
                when (side) {
                    NotePlacement.ABOVE -> (n.bottom <= a.top + 0.5f) shouldBe true
                    NotePlacement.BELOW -> (n.top >= a.bottom - 0.5f) shouldBe true
                    NotePlacement.LEFT -> (n.right <= a.left + 0.5f) shouldBe true
                    else -> (n.left >= a.right - 0.5f) shouldBe true
                }
                (EdgeId("l1") in res.layout.edges) shouldBe true
            }
        }

        test("no hint and NEAR leave the ELK layout untouched") {
            val layout = layoutOf()
            run(null, layout).first.layout shouldBe layout
            run(NotePlacement.NEAR, layout).first.layout shouldBe layout
        }

        test("a free-standing note and a note with an unresolvable anchor keep the ELK position") {
            val layout = layoutOf()
            run(NotePlacement.ABOVE, layout, diagramWith(NotePlacement.ABOVE, anchor = "missing")).first.layout shouldBe layout
        }

        test("an impossible side is reported once as W004 and falls back to the default placement") {
            val blocked = layoutOf(mapOf("roof" to rect(-400f, -400f, 1800f, 676f)))
            val (res, misses) = run(NotePlacement.ABOVE, blocked)
            misses shouldHaveSize 1
            misses.single().noteId shouldBe "n1"
            misses.single().wanted shouldBe NotePlacement.ABOVE
            res.hintNotHonoured shouldHaveSize 1
        }

        test("a sequence diagram is never touched") {
            val layout = layoutOf()
            val seq = diagramWith(NotePlacement.ABOVE, type = DiagramType.SEQUENCE)
            run(NotePlacement.ABOVE, layout, seq).first.layout shouldBe layout
        }

        test("two runs give equal results") {
            run(NotePlacement.LEFT).first shouldBe run(NotePlacement.LEFT).first
        }
    })
