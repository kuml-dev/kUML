package dev.kuml.layout.bridge.note

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.label.Sysml2LabelSizing
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.polylinePoints
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 120, end to end: a diagram built from real model elements, laid out by the real ELK engine. The edge id the
 * label lines are keyed by must be the id of the layout edge, so the hinted note keeps clear of the estimated centre label.
 */
class UmlNoteLabelEndToEndTest :
    FunSpec({
        val name = "is managed by the responsible department"

        fun diagram(placement: NotePlacement?) =
            KumlDiagram(
                id = "d",
                name = "D",
                type = DiagramType.CLASS,
                elements =
                    listOf(
                        UmlClass(id = "a", name = "A"),
                        UmlClass(id = "b", name = "B"),
                        UmlAssociation(
                            id = "e1",
                            name = name,
                            ends = listOf(UmlAssociationEnd(typeId = "a"), UmlAssociationEnd(typeId = "b")),
                        ),
                        UmlComment(id = "n1", body = "About A", placement = placement),
                        UmlCommentLink(id = "l1", commentId = "n1", annotatedElementId = "a"),
                    ),
            )

        fun layout(d: KumlDiagram) =
            LayoutSpecRunner
                .layout(
                    engine = ElkLayoutEngine(),
                    graph = UmlLayoutBridge.toLayoutGraph(diagram = d),
                    hints = LayoutHints.DEFAULT,
                    spec = null,
                    kind = SpecDiagramKind.UML_CLASS,
                ).result

        fun hits(
            a: Rect,
            b: Rect,
        ) = a.origin.x < b.origin.x + b.size.width &&
            b.origin.x < a.origin.x + a.size.width &&
            a.origin.y < b.origin.y + b.size.height &&
            b.origin.y < a.origin.y + a.size.height

        test("the label lines are keyed by an id that exists in the real layout, and the hinted note keeps clear of the label") {
            for (side in listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val d = diagram(side)
                val raw = layout(d)
                (EdgeId("e1") in raw.edges) shouldBe true
                UmlNotePlacement.centreLabelLines(d).keys shouldBe setOf("e1")

                val (res, misses) = NotePlacementMissCollector.collecting { UmlNotePlacement.place(diagram = d, layout = raw) }
                (misses.size <= 1) shouldBe true
                val pts: List<Point> =
                    res.layout.edges
                        .getValue(EdgeId("e1"))
                        .polylinePoints()
                val mid = polylineMidpoint(pts)
                val size = Sysml2LabelSizing.sizeOf(listOf(name))
                val label = Rect(origin = Point(x = mid.x - size.width / 2f, y = mid.y - size.height / 2f), size = size)
                val note =
                    res.layout.nodes
                        .getValue(NodeId("n1"))
                        .bounds
                hits(note, label) shouldBe false
            }
        }
    })
