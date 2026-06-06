package dev.kuml.layout.grid

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.PortId
import dev.kuml.layout.RelativeConstraint
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun node(
    id: String,
    width: Float = 100f,
    height: Float = 60f,
    hints: NodeHints = NodeHints.NONE,
): LayoutNode = LayoutNode(NodeId(id), Size(width, height), hints)

private fun edge(
    id: String,
    from: String,
    to: String,
    hints: EdgeHints = EdgeHints.NONE,
    fromPort: String? = null,
    toPort: String? = null,
): LayoutEdge =
    LayoutEdge(
        EdgeId(id),
        source = EndpointRef(NodeId(from), fromPort?.let { PortId(it) }),
        target = EndpointRef(NodeId(to), toPort?.let { PortId(it) }),
        hints = hints,
    )

class GridLayoutEngineTest :
    FunSpec({

        val engine = GridLayoutEngine()

        test("engine identifies itself as kuml.grid with deterministic capabilities") {
            engine.id shouldBe LayoutEngineId("kuml.grid")
            engine.capabilities.deterministic shouldBe true
            engine.capabilities.respectsGridHints shouldBe true
            engine.capabilities.respectsRelativeConstraints shouldBe true
            engine.capabilities.supportedEdgeStyles shouldContain EdgeRouteStyle.OrthogonalRounded
            engine.capabilities.supportedEdgeStyles shouldContain EdgeRouteStyle.Direct
            engine.capabilities.supportedEdgeStyles shouldContain EdgeRouteStyle.Bezier
        }

        test("empty graph produces an empty result with valid canvas") {
            val result = engine.layout(LayoutGraph(emptyList(), emptyList()))
            result.nodes.shouldHaveSize(0)
            result.edges.shouldHaveSize(0)
            result.canvas.width shouldBeGreaterThan 0f // includes the half-gap on each side
            result.canvas.height shouldBeGreaterThan 0f
        }

        test("three unhinted nodes are placed in a single row by default reading order") {
            val graph = LayoutGraph(nodes = listOf(node("a"), node("b"), node("c")), edges = emptyList())
            val result = engine.layout(graph)
            val a = result.nodes.getValue(NodeId("a")).bounds
            val b = result.nodes.getValue(NodeId("b")).bounds
            val c = result.nodes.getValue(NodeId("c")).bounds
            // Reading-order: left-to-right
            (a.origin.x < b.origin.x) shouldBe true
            (b.origin.x < c.origin.x) shouldBe true
            // No overlap on the X axis
            (a.origin.x + a.size.width <= b.origin.x) shouldBe true
            (b.origin.x + b.size.width <= c.origin.x) shouldBe true
        }

        test("explicit gridCol/gridRow hints are honored") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            node("a", hints = NodeHints(gridCol = 0, gridRow = 0)),
                            node("b", hints = NodeHints(gridCol = 1, gridRow = 1)),
                            node("c", hints = NodeHints(gridCol = 0, gridRow = 1)),
                        ),
                    edges = emptyList(),
                )
            val result = engine.layout(graph)
            val a = result.nodes.getValue(NodeId("a")).bounds
            val b = result.nodes.getValue(NodeId("b")).bounds
            val c = result.nodes.getValue(NodeId("c")).bounds
            // a is top-left, b is bottom-right, c is bottom-left → c.x < b.x, a.y < c.y
            (a.origin.y < c.origin.y) shouldBe true
            (c.origin.x < b.origin.x) shouldBe true
            (a.origin.x == c.origin.x) shouldBe true
            (b.origin.y == c.origin.y) shouldBe true
            result.warnings.none { it.code == "hint.conflict.gridSlot" } shouldBe true
        }

        test("conflicting explicit slots produce a warning and shift the loser to the right") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            node("a", hints = NodeHints(gridCol = 0, gridRow = 0)),
                            node("b", hints = NodeHints(gridCol = 0, gridRow = 0)),
                        ),
                    edges = emptyList(),
                )
            val result = engine.layout(graph)
            result.warnings.any { it.code == "hint.conflict.gridSlot" } shouldBe true
            val a = result.nodes.getValue(NodeId("a")).bounds
            val b = result.nodes.getValue(NodeId("b")).bounds
            // a kept its requested slot; b shifted right of a
            (a.origin.x < b.origin.x) shouldBe true
        }

        test("Above relative constraint places node above its anchor") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            node("anchor", hints = NodeHints(gridCol = 1, gridRow = 1)),
                            node(
                                "follower",
                                hints =
                                    NodeHints(
                                        relative = listOf(RelativeConstraint.Above(NodeId("anchor"))),
                                    ),
                            ),
                        ),
                    edges = emptyList(),
                )
            val result = engine.layout(graph)
            val anchor = result.nodes.getValue(NodeId("anchor")).bounds
            val follower = result.nodes.getValue(NodeId("follower")).bounds
            (follower.origin.y < anchor.origin.y) shouldBe true
        }

        test("RightOf places node to the right of its anchor") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            node("anchor", hints = NodeHints(gridCol = 0, gridRow = 0)),
                            node(
                                "follower",
                                hints =
                                    NodeHints(
                                        relative = listOf(RelativeConstraint.RightOf(NodeId("anchor"))),
                                    ),
                            ),
                        ),
                    edges = emptyList(),
                )
            val result = engine.layout(graph)
            val anchor = result.nodes.getValue(NodeId("anchor")).bounds
            val follower = result.nodes.getValue(NodeId("follower")).bounds
            (follower.origin.x > anchor.origin.x) shouldBe true
        }

        test("default edge style OrthogonalRounded yields routes with mid waypoints") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            node("a", hints = NodeHints(gridCol = 0, gridRow = 0)),
                            node("b", hints = NodeHints(gridCol = 2, gridRow = 0)),
                        ),
                    edges = listOf(edge("e", "a", "b")),
                )
            val result = engine.layout(graph)
            val route = result.edges.getValue(EdgeId("e"))
            route.shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            route.waypoints.size shouldBe 2
        }

        test("EdgeHints.routeStyle overrides the default per edge") {
            val graph =
                LayoutGraph(
                    nodes = listOf(node("a"), node("b")),
                    edges =
                        listOf(
                            edge("e", "a", "b", hints = EdgeHints(routeStyle = EdgeRouteStyle.Direct)),
                        ),
                )
            val result = engine.layout(graph)
            result.edges.getValue(EdgeId("e")).shouldBeInstanceOf<EdgeRoute.Direct>()
        }

        test("Bezier style produces two control points offset from the direct line") {
            val graph =
                LayoutGraph(
                    nodes = listOf(node("a"), node("b")),
                    edges = listOf(edge("e", "a", "b")),
                )
            val result =
                engine.layout(graph, LayoutHints(defaultEdgeStyle = EdgeRouteStyle.Bezier))
            val route = result.edges.getValue(EdgeId("e"))
            route.shouldBeInstanceOf<EdgeRoute.Bezier>()
            route.controlPoints.size shouldBe 2
        }

        test("port-bound endpoints attach the edge to the allocated port position") {
            val graph =
                LayoutGraph(
                    nodes = listOf(node("a"), node("b")),
                    edges = listOf(edge("e", "a", "b", fromPort = "p1", toPort = "p2")),
                )
            val result = engine.layout(graph)
            val aLayout = result.nodes.getValue(NodeId("a"))
            val bLayout = result.nodes.getValue(NodeId("b"))
            (PortId("p1") in aLayout.ports) shouldBe true
            (PortId("p2") in bLayout.ports) shouldBe true
            val route = result.edges.getValue(EdgeId("e"))
            // The route's source must match the allocated port, not the node edge.
            route.source shouldBe aLayout.ports.getValue(PortId("p1"))
            route.target shouldBe bLayout.ports.getValue(PortId("p2"))
        }

        test("graph above maxRecommendedNodes triggers a performance warning") {
            val many =
                (0 until 520).map { node("n$it") }
            val result = engine.layout(LayoutGraph(many, emptyList()))
            result.warnings.any { it.code == "engine.performance.large_graph" } shouldBe true
        }

        test("layout is deterministic — same input produces byte-identical result") {
            val graph =
                LayoutGraph(
                    nodes = listOf(node("a"), node("b"), node("c")),
                    edges = listOf(edge("e1", "a", "b"), edge("e2", "b", "c")),
                )
            val r1 = engine.layout(graph, LayoutHints(deterministicSeed = 42L))
            val r2 = engine.layout(graph, LayoutHints(deterministicSeed = 42L))
            r1 shouldBe r2
        }
    })
