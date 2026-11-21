@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Nacharbeit 129 (UML): route and label plan of a self-association agree on the side, and the side avoids a foreign edge. */
class UmlSelfLoopSideSvgTest :
    FunSpec({
        val padding = 16f

        fun diagram(withNeighbour: Boolean): Pair<KumlDiagram, LayoutResult> {
            val node = UmlClass(id = "Node", name = "Node")
            val leaf = UmlClass(id = "Leaf", name = "Leaf")
            val loop =
                UmlAssociation(
                    id = "loop",
                    ends =
                        listOf(
                            UmlAssociationEnd(
                                typeId = "Node",
                                role = "parent",
                                multiplicity = Multiplicity(0, 1),
                                multiplicityExplicit = true,
                            ),
                            UmlAssociationEnd(
                                typeId = "Node",
                                role = "children",
                                multiplicity = Multiplicity(0, null),
                                multiplicityExplicit = true,
                            ),
                        ),
                )
            val feeds =
                UmlAssociation(
                    id = "feeds",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Node", multiplicity = Multiplicity(1, 1), multiplicityExplicit = true),
                            UmlAssociationEnd(typeId = "Leaf", multiplicity = Multiplicity(0, null), multiplicityExplicit = true),
                        ),
                )
            val elements = if (withNeighbour) listOf(node, leaf, loop, feeds) else listOf(node, leaf, loop)
            val nodes =
                mapOf(
                    NodeId("Node") to NodeLayout(Rect(Point(200f, 100f), Size(160f, 120f))),
                    NodeId("Leaf") to NodeLayout(Rect(Point(520f, 100f), Size(120f, 120f))),
                )
            val edges =
                buildMap<EdgeId, EdgeRoute> {
                    put(
                        EdgeId("loop"),
                        EdgeRoute.OrthogonalRounded(Point(360f, 140f), Point(360f, 190f), listOf(Point(350f, 140f), Point(350f, 190f)), 6f),
                    )
                    if (withNeighbour) put(EdgeId("feeds"), EdgeRoute.Direct(Point(360f, 160f), Point(520f, 160f)))
                }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(700f, 300f),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            return KumlDiagram(name = "d", type = DiagramType.CLASS, elements = elements) to layout
        }

        test("busy right side: label plan puts every loop label left of the node") {
            val (d, layout) = diagram(withNeighbour = true)
            val plan = UmlAssociationLabelPlan.planFor(diagram = d, layout = layout, theme = PlainTheme())
            val labels = plan.getValue("loop").values
            labels.isNotEmpty() shouldBe true
            for (l in labels) (l.rect.origin.x + l.rect.size.width <= 200f + 0.5f) shouldBe true
        }

        test("free right side: label plan keeps the loop labels right of the node") {
            val (d, layout) = diagram(withNeighbour = false)
            val plan = UmlAssociationLabelPlan.planFor(diagram = d, layout = layout, theme = PlainTheme())
            for (l in plan.getValue("loop").values) (l.rect.origin.x >= 360f - 0.5f) shouldBe true
        }

        test("the drawn loop is on the same (left) side as its labels") {
            val (d, layout) = diagram(withNeighbour = true)
            val svg = KumlSvgRenderer.toSvg(diagram = d, layoutResult = layout, theme = PlainTheme())
            // left border 200 + padding, loop extends 32 further left
            svg shouldContain " ${(200 + padding - 32).toInt()} "
        }
    })
