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
import dev.kuml.layout.bridge.label.Sysml2LabelSizing
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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

        // Nacharbeit 120: a hinted note avoids the centre label of an edge. The edge is vertical at x = 700, so only its
        // label (centred on (700, 330), as wide as the name) can reach the note position right of the anchor.
        val edgeId = "e1"
        val longName = "a rather long association name"

        fun withEdge(extraName: String?): Pair<LayoutResult, KumlDiagram> {
            val base = layoutOf()
            val layout =
                base.copy(
                    edges =
                        base.edges +
                            (EdgeId(edgeId) to EdgeRoute.Direct(source = Point(x = 700f, y = 250f), target = Point(x = 700f, y = 410f))),
                )
            val diagram =
                diagramWith(NotePlacement.RIGHT).let { d ->
                    d.copy(
                        elements =
                            d.elements +
                                UmlAssociation(
                                    id = edgeId,
                                    name = extraName,
                                    ends = listOf(UmlAssociationEnd(typeId = "a"), UmlAssociationEnd(typeId = "a")),
                                ),
                    )
                }
            return layout to diagram
        }

        fun noteOf(res: NotePlacementResult) =
            res.layout.nodes
                .getValue(NodeId("n1"))
                .bounds

        fun labelRectOf(lines: List<String>): Rect {
            val size = Sysml2LabelSizing.sizeOf(lines)
            return rect(x = 700f - size.width / 2f, y = 330f - size.height / 2f, w = size.width, h = size.height)
        }

        fun Rect.hits(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

        test("centre label lines are the stereotype header then the name; nothing without either") {
            val d =
                diagramWith(NotePlacement.RIGHT).let {
                    it.copy(
                        elements =
                            it.elements +
                                UmlAssociation(
                                    id = "x",
                                    name = "owns",
                                    ends = emptyList(),
                                    stereotypes = listOf("a", "b"),
                                ) +
                                UmlAssociation(id = "y", name = null, ends = emptyList()),
                    )
                }
            val lines = UmlNotePlacement.centreLabelLines(d)
            lines["x"] shouldBe listOf("«a, b»", "owns")
            (lines["y"] == null) shouldBe true
        }

        test("a hinted note avoids the centre label of an edge") {
            val (layout, diagram) = withEdge(longName)
            val (res, misses) = run(NotePlacement.RIGHT, layout, diagram)
            val n = noteOf(res)
            n.hits(labelRectOf(listOf(longName))) shouldBe false
            (NodeId("n1") in res.layout.nodes) shouldBe true
            (misses.size <= 1) shouldBe true
            if (misses.isEmpty()) (n.left >= a.right - 0.5f) shouldBe true
        }

        test("with the label elsewhere (no name) the hint is honoured exactly as without the edge") {
            val (layout, diagram) = withEdge(null)
            val plain = run(NotePlacement.RIGHT).first
            val (res, misses) = run(NotePlacement.RIGHT, layout, diagram)
            misses.shouldBeEmpty()
            noteOf(res) shouldBe noteOf(plain)
        }

        test("without the label obstacle the same note would have sat on the label") {
            // guards the test above: the unlabelled position collides with the label estimate
            val plain = run(NotePlacement.RIGHT).first
            noteOf(plain).hits(labelRectOf(listOf(longName))) shouldBe true
        }

        test("an unhinted note and a diagram without a hinted note stay byte-identical with labelled edges") {
            val (layout, diagram) = withEdge(longName)
            val unhinted = diagram.copy(elements = diagram.elements.map { if (it is UmlComment) it.copy(placement = null) else it })
            run(null, layout, unhinted).first.layout shouldBe layout
        }

        test("an edge with fewer than two points does not crash the label obstacle") {
            val (layout, diagram) = withEdge(longName)
            val degenerate =
                layout.copy(
                    edges =
                        layout.edges +
                            (EdgeId(edgeId) to EdgeRoute.Direct(source = Point(x = 700f, y = 330f), target = Point(x = 700f, y = 330f))),
                )
            run(NotePlacement.RIGHT, degenerate, diagram)
                .first.layout.nodes.keys shouldNotBe emptySet<NodeId>()
        }
    })
