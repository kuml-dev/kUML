package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayerPin
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutPort
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.PortSide
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** C3-1 — ELK-Ports, externe Labels und Ebenen-Hints (inkl. Spike zu `layerChoiceConstraint`). */
class ElkPortsAndLayerHintsTest :
    FunSpec({
        val engine = ElkLayoutEngine()

        fun route(points: EdgeRoute): List<Point> =
            when (points) {
                is EdgeRoute.Direct -> listOf(points.source, points.target)
                is EdgeRoute.OrthogonalRounded -> listOf(points.source) + points.waypoints + listOf(points.target)
                is EdgeRoute.TreeRounded -> listOf(points.source) + points.waypoints + listOf(points.target)
                is EdgeRoute.Bezier -> listOf(points.source) + points.controlPoints + listOf(points.target)
            }

        test("capabilities advertise ports and layer hints") {
            engine.capabilities.supportsPorts shouldBe true
            engine.capabilities.supportsLayerHints shouldBe true
        }

        test("ports keep side and order, edge ends at the port centre and never crosses the node body") {
            val a = LayoutNode(id = NodeId("A"), intrinsicSize = Size(width = 100f, height = 40f))
            val b =
                LayoutNode(
                    id = NodeId("B"),
                    intrinsicSize = Size(width = 120f, height = 80f),
                    ports =
                        listOf(
                            LayoutPort(id = PortId("B::in1"), side = PortSide.West, order = 0),
                            LayoutPort(id = PortId("B::in2"), side = PortSide.West, order = 1),
                            LayoutPort(id = PortId("B::out"), side = PortSide.East, order = 0, labelSize = Size(width = 30f, height = 14f)),
                        ),
                )
            val graph =
                LayoutGraph(
                    nodes = listOf(a, b),
                    edges =
                        listOf(
                            LayoutEdge(
                                id = EdgeId("e1"),
                                source = EndpointRef(nodeId = NodeId("A")),
                                target = EndpointRef(nodeId = NodeId("B"), portId = PortId("B::in1")),
                            ),
                            LayoutEdge(
                                id = EdgeId("e2"),
                                source = EndpointRef(nodeId = NodeId("A")),
                                target = EndpointRef(nodeId = NodeId("B"), portId = PortId("B::in2")),
                            ),
                        ),
                )
            val result = engine.layout(graph = graph, hints = LayoutHints(direction = LayoutDirection.LeftToRight))
            val nb = result.nodes.getValue(NodeId("B"))
            val in1 = nb.ports.getValue(PortId("B::in1"))
            val in2 = nb.ports.getValue(PortId("B::in2"))
            val out = nb.ports.getValue(PortId("B::out"))
            // Seiten: West-Ports auf der linken, East-Port auf der rechten Kante
            abs(in1.x - nb.bounds.origin.x) shouldBeLessThanOrEqual 1f
            abs(in2.x - nb.bounds.origin.x) shouldBeLessThanOrEqual 1f
            abs(out.x - (nb.bounds.origin.x + nb.bounds.size.width)) shouldBeLessThanOrEqual 1f
            // feste Reihenfolge entlang der Seite
            in1.y shouldBeLessThan in2.y
            // Label für den Port wurde platziert
            nb.labels.containsKey("port:B::out") shouldBe true
            // Kantenendpunkt am Port-Mittelpunkt
            for ((edgeId, port) in listOf(EdgeId("e1") to in1, EdgeId("e2") to in2)) {
                val pts = route(result.edges.getValue(edgeId))
                val end = pts.last()
                abs(end.x - port.x) shouldBeLessThanOrEqual 1f
                abs(end.y - port.y) shouldBeLessThanOrEqual 1f
                // kein Segment durchquert das (um 1 px verkleinerte) Innere von B
                val l = nb.bounds.origin.x + 1f
                val r = nb.bounds.origin.x + nb.bounds.size.width - 1f
                val t = nb.bounds.origin.y + 1f
                val bt = nb.bounds.origin.y + nb.bounds.size.height - 1f
                for (i in 0 until pts.size - 1) {
                    val p = pts[i]
                    val q = pts[i + 1]
                    // achsenparallele Segmente (orthogonales Routing) — Innenraum-Test per Mittelpunkt
                    val mx = (p.x + q.x) / 2f
                    val my = (p.y + q.y) / 2f
                    val inside = mx > l && mx < r && my > t && my < bt
                    inside shouldBe false
                }
            }
        }

        test("external node label reserves space above the node") {
            val n =
                LayoutNode(
                    id = NodeId("F"),
                    intrinsicSize = Size(width = 60f, height = 6f),
                    externalLabelSize = Size(width = 40f, height = 14f),
                )
            val m = LayoutNode(id = NodeId("M"), intrinsicSize = Size(width = 60f, height = 30f))
            val graph =
                LayoutGraph(
                    nodes = listOf(n, m),
                    edges =
                        listOf(
                            LayoutEdge(
                                id = EdgeId("e"),
                                source = EndpointRef(nodeId = NodeId("F")),
                                target = EndpointRef(nodeId = NodeId("M")),
                            ),
                        ),
                )
            val result = engine.layout(graph = graph)
            val label =
                result.nodes
                    .getValue(NodeId("F"))
                    .labels
                    .getValue("node")
            label.size.width shouldBe 40f
            label.origin.y shouldBeLessThan
                result.nodes
                    .getValue(NodeId("F"))
                    .bounds.origin.y
        }

        test("layerIndex orders flat nodes by layer, also without edges") {
            val nodes =
                (0 until 6).map { i ->
                    LayoutNode(
                        id = NodeId("n$i"),
                        intrinsicSize = Size(width = 80f, height = 30f),
                        hints =
                            NodeHints(
                                layerIndex =
                                    i / 2,
                            ),
                    )
                }
            val result =
                engine.layout(
                    graph = LayoutGraph(nodes = nodes, edges = emptyList()),
                    hints = LayoutHints(direction = LayoutDirection.LeftToRight),
                )
            val xs =
                (0 until 6).map {
                    result.nodes
                        .getValue(NodeId("n$it"))
                        .bounds.origin.x
                }
            // gleiche Ebene = gleiche x-Koordinate, höhere Ebene = weiter rechts
            abs(xs[0] - xs[1]) shouldBeLessThanOrEqual 1f
            abs(xs[2] - xs[3]) shouldBeLessThanOrEqual 1f
            xs[2] shouldBeGreaterThan xs[0] + 1f
            xs[4] shouldBeGreaterThan xs[2] + 1f
        }

        test("layerIndex inside a compound group is reported as ignored (ELK limitation)") {
            val gid = GroupId("g")
            val nodes =
                (0 until 2).map { i ->
                    LayoutNode(
                        id = NodeId("n$i"),
                        intrinsicSize = Size(width = 80f, height = 30f),
                        hints = NodeHints(layerIndex = i),
                        groupId = gid,
                    )
                }
            val graph =
                LayoutGraph(
                    nodes = nodes,
                    edges = emptyList(),
                    groups =
                        listOf(
                            LayoutGroup(
                                id = gid,
                                padding = Insets(top = 20f, right = 10f, bottom = 10f, left = 10f),
                                layoutAsCompound = true,
                            ),
                        ),
                )
            val result = engine.layout(graph = graph, hints = LayoutHints(direction = LayoutDirection.LeftToRight))
            result.warnings.any { it.code == "hint.ignored.layerInCompound" } shouldBe true
        }

        test("layerPin First and Last land on the extreme layers") {
            val edges =
                listOf(
                    LayoutEdge(id = EdgeId("e1"), source = EndpointRef(nodeId = NodeId("a")), target = EndpointRef(nodeId = NodeId("b"))),
                    LayoutEdge(id = EdgeId("e2"), source = EndpointRef(nodeId = NodeId("b")), target = EndpointRef(nodeId = NodeId("c"))),
                )
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            LayoutNode(id = NodeId("a"), intrinsicSize = Size(width = 60f, height = 30f)),
                            LayoutNode(id = NodeId("b"), intrinsicSize = Size(width = 60f, height = 30f)),
                            LayoutNode(id = NodeId("c"), intrinsicSize = Size(width = 60f, height = 30f)),
                            LayoutNode(
                                id = NodeId("first"),
                                intrinsicSize = Size(width = 60f, height = 30f),
                                hints = NodeHints(layerPin = LayerPin.First),
                            ),
                            LayoutNode(
                                id = NodeId("last"),
                                intrinsicSize = Size(width = 60f, height = 30f),
                                hints = NodeHints(layerPin = LayerPin.Last),
                            ),
                        ),
                    edges = edges,
                )
            val result = engine.layout(graph = graph, hints = LayoutHints(direction = LayoutDirection.LeftToRight))
            val xs = result.nodes.mapValues { it.value.bounds.origin.x }
            val minX = xs.values.min()
            val maxX = xs.values.max()
            abs(xs.getValue(NodeId("first")) - minX) shouldBeLessThanOrEqual 1f
            abs(xs.getValue(NodeId("last")) - maxX) shouldBeLessThanOrEqual 1f
        }

        test("layerIndex on members of a non-compound swimlane group still takes effect") {
            val nodes =
                (0 until 6).map { i ->
                    LayoutNode(
                        id = NodeId("n$i"),
                        intrinsicSize = Size(width = 80f, height = 30f),
                        groupId = GroupId("lane"),
                        hints = NodeHints(layerIndex = i / 2),
                    )
                }
            val result =
                engine.layout(
                    graph = LayoutGraph(nodes = nodes, edges = emptyList(), groups = listOf(LayoutGroup(id = GroupId("lane")))),
                    hints = LayoutHints(direction = LayoutDirection.LeftToRight),
                )
            val xs =
                (0 until 6).map {
                    result.nodes
                        .getValue(NodeId("n$it"))
                        .bounds.origin.x
                }
            abs(xs[0] - xs[1]) shouldBeLessThanOrEqual 1f
            xs[2] shouldBeGreaterThan xs[0] + 1f
            xs[4] shouldBeGreaterThan xs[2] + 1f
        }

        test("a graph without ports or hints is laid out as before") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            LayoutNode(id = NodeId("A"), intrinsicSize = Size(width = 100f, height = 50f)),
                            LayoutNode(id = NodeId("B"), intrinsicSize = Size(width = 100f, height = 50f)),
                        ),
                    edges =
                        listOf(
                            LayoutEdge(
                                id = EdgeId("e"),
                                source = EndpointRef(nodeId = NodeId("A")),
                                target = EndpointRef(nodeId = NodeId("B")),
                            ),
                        ),
                )
            val result = engine.layout(graph = graph)
            val a = result.nodes.getValue(NodeId("A"))
            val b = result.nodes.getValue(NodeId("B"))
            a.ports shouldBe emptyMap()
            a.labels shouldBe emptyMap()
            (b.bounds.origin.y - a.bounds.origin.y) shouldBe 140f // 50 + Standard-Layer-Abstand 90
        }
    })
