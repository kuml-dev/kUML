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

        // Nacharbeit 132: right and left bands blocked -> loop above (TOP), plus top blocked -> below (BOTTOM).
        fun verticalDiagram(
            nodeY: Float,
            blockTop: Boolean,
            canvasHeight: Float,
        ): Pair<KumlDiagram, LayoutResult> {
            val node = UmlClass(id = "Node", name = "Node")
            val right = UmlClass(id = "Right", name = "Right")
            val leftC = UmlClass(id = "LeftC", name = "LeftC")
            val up = UmlClass(id = "Up", name = "Up")

            fun assoc(
                id: String,
                a: String,
                b: String,
            ) = UmlAssociation(
                id = id,
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = a, multiplicity = Multiplicity(1, 1), multiplicityExplicit = true),
                        UmlAssociationEnd(typeId = b, multiplicity = Multiplicity(0, null), multiplicityExplicit = true),
                    ),
            )
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
            val elements =
                mutableListOf<dev.kuml.core.model.KumlElement>(
                    node,
                    right,
                    leftC,
                    loop,
                    assoc("er", "Node", "Right"),
                    assoc("el", "LeftC", "Node"),
                )
            val nodes =
                mutableMapOf(
                    NodeId("Node") to NodeLayout(Rect(Point(300f, nodeY), Size(160f, 120f))),
                    NodeId("Right") to NodeLayout(Rect(Point(620f, nodeY), Size(120f, 120f))),
                    NodeId("LeftC") to NodeLayout(Rect(Point(40f, nodeY), Size(120f, 120f))),
                )
            val edges =
                mutableMapOf<EdgeId, EdgeRoute>(
                    EdgeId("loop") to
                        EdgeRoute.OrthogonalRounded(
                            Point(460f, nodeY + 40),
                            Point(460f, nodeY + 90),
                            listOf(
                                Point(450f, nodeY + 40),
                                Point(
                                    450f,
                                    nodeY + 90,
                                ),
                            ),
                            6f,
                        ),
                    EdgeId("er") to EdgeRoute.Direct(Point(460f, nodeY + 60), Point(620f, nodeY + 60)),
                    EdgeId("el") to EdgeRoute.Direct(Point(160f, nodeY + 60), Point(300f, nodeY + 60)),
                )
            if (blockTop) {
                elements += up
                elements += assoc("eu", "Up", "Node")
                nodes[NodeId("Up")] = NodeLayout(Rect(Point(340f, nodeY - 120f), Size(80f, 40f)))
                edges[EdgeId("eu")] = EdgeRoute.Direct(Point(380f, nodeY - 80f), Point(380f, nodeY))
            }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(800f, canvasHeight),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            return KumlDiagram(name = "d", type = DiagramType.CLASS, elements = elements) to layout
        }

        fun viewBox(svg: String): List<Float> =
            Regex("""viewBox="(-?[0-9.]+) (-?[0-9.]+) ([0-9.]+) ([0-9.]+)"""")
                .find(svg)!!
                .groupValues
                .drop(3)
                .map { it.toFloat() }

        fun textYs(svg: String): List<Float> =
            Regex("""<text[^>]* y="(-?[0-9.]+)"""").findAll(svg).map { it.groupValues[1].toFloat() }.toList()

        test("right and left busy: loop and labels go above the node, label plan keeps the legs clear (TOP)") {
            val (d, layout) = verticalDiagram(nodeY = 32f, blockTop = false, canvasHeight = 300f)
            val plan = UmlAssociationLabelPlan.planFor(diagram = d, layout = layout, theme = PlainTheme())
            val legL = 300f + 160f * 0.30f
            val legR = 300f + 160f * 0.70f
            val loopLabels = plan.getValue("loop").values
            loopLabels.isNotEmpty() shouldBe true
            for (l in loopLabels) {
                val r = l.rect
                val between = r.origin.x < legR && r.origin.x + r.size.width > legL && r.origin.y < 32f && r.origin.y + r.size.height > 0f
                between shouldBe false
            }
            val svg = KumlSvgRenderer.toSvg(diagram = d, layoutResult = layout, theme = PlainTheme())
            val (w, h) = viewBox(svg)
            // the stacked end labels above the loop reach y < 0 in layout space: everything was shifted down and the canvas grew
            (h > 300f + 2 * padding) shouldBe true
            (w > 0f) shouldBe true
            for (y in textYs(svg)) ((y >= 0f) && (y <= h)) shouldBe true
        }

        test("right, left and top busy: loop below the node and the canvas grows to contain it (BOTTOM)") {
            // node bottom = 120 + 120 = 240; the loop reaches 272, beyond the 250 px canvas, which must grow
            val (d, layout) = verticalDiagram(nodeY = 120f, blockTop = true, canvasHeight = 250f)
            val svg = KumlSvgRenderer.toSvg(diagram = d, layoutResult = layout, theme = PlainTheme())
            val (_, h) = viewBox(svg)
            // loop outer line at 120 + 120 + 32 = 272 plus padding, plus its labels
            (h >= 272f + padding) shouldBe true
            for (y in textYs(svg)) ((y >= 0f) && (y <= h)) shouldBe true
        }
    })
