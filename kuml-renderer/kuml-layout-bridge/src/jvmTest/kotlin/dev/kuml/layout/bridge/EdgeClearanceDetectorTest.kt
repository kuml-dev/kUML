@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** PAL wave J: the shared clearance detector on synthetic geometry (positive, negative, boundary values). */
class EdgeClearanceDetectorTest :
    FunSpec({
        timeout = 30_000L

        fun p(
            x: Float,
            y: Float,
        ) = Point(x = x, y = y)

        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = p(x, y), size = Size(width = w, height = h))

        val box = rect(x = 100f, y = 100f, w = 100f, h = 50f)

        test("edgeNodeGap measures the distance of a free segment and respects the 12 px boundary") {
            // vertical run at x = 100 - gap, long enough that the exempt ends do not hide it
            fun gapAt(distance: Float) = edgeNodeGap(route = listOf(p(100f - distance, 0f), p(100f - distance, 300f)), rect = box)!!
            gapAt(11.9f) shouldBe (11.9f plusOrMinus 0.01f)
            gapAt(12f) shouldBe (12f plusOrMinus 0.01f)
            gapAt(16f) shouldBe (16f plusOrMinus 0.01f)
            (gapAt(11.9f) < EDGE_NODE_MIN_GAP_PX) shouldBe true
            (gapAt(12f) < EDGE_NODE_MIN_GAP_PX) shouldBe false
        }

        test("a route through the node has gap 0") {
            edgeNodeGap(route = listOf(p(150f, 0f), p(150f, 300f)), rect = box) shouldBe 0f
        }

        test("the first and last 12 px of a route belong to its own attachment") {
            // the route ends right at the box edge: without the exemption its gap would be 0
            val route = listOf(p(150f, 0f), p(150f, 100f))
            edgeNodeGap(route = route, rect = box)!! shouldBe (12f plusOrMinus 0.01f)
            edgeNodeGap(route = route, rect = box, exemptNearEndpointPx = 0f) shouldBe 0f
        }

        test("a route shorter than twice the exemption has nothing left to measure") {
            edgeNodeGap(route = listOf(p(0f, 0f), p(10f, 0f)), rect = box).shouldBeNull()
        }

        test("trimEnds cuts along the polyline, around corners") {
            val trimmed = trimEnds(route = listOf(p(0f, 0f), p(10f, 0f), p(10f, 100f)), exemptPx = 12f)
            trimmed.first() shouldBe p(10f, 2f)
            trimmed.last() shouldBe p(10f, 88f)
        }

        test("parallel overlap needs a common projection") {
            val a = p(0f, 0f) to p(0f, 100f)
            parallelOverlap(a, p(10f, 50f) to p(10f, 200f)) shouldBe 50f
            parallelOverlap(a, p(10f, 100f) to p(10f, 200f)) shouldBe 0f
            parallelOverlap(a, p(0f, 50f) to p(40f, 50f)) shouldBe 0f
        }

        fun twoEdgeGraph(
            gap: Float,
            shareNode: Boolean = false,
        ): Pair<LayoutGraph, LayoutResult> {
            val ids = listOf("a", "b", "c", "d").map { NodeId(it) }
            val nodes = ids.map { LayoutNode(id = it, intrinsicSize = Size(width = 40f, height = 20f)) }
            val e1 = LayoutEdge(id = EdgeId("e1"), source = EndpointRef(NodeId("a")), target = EndpointRef(NodeId("b")))
            val e2 =
                LayoutEdge(
                    id = EdgeId("e2"),
                    source = EndpointRef(NodeId(if (shareNode) "a" else "c")),
                    target = EndpointRef(NodeId(if (shareNode) "b" else "d")),
                )
            val graph = LayoutGraph(nodes = nodes, edges = listOf(e1, e2))
            val result =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 1000f, height = 1000f),
                    nodes =
                        mapOf(
                            NodeId("a") to NodeLayout(bounds = rect(0f, 0f, 40f, 20f)),
                            NodeId("b") to NodeLayout(bounds = rect(0f, 500f, 40f, 20f)),
                            NodeId("c") to NodeLayout(bounds = rect(300f, 0f, 40f, 20f)),
                            NodeId("d") to NodeLayout(bounds = rect(300f, 500f, 40f, 20f)),
                        ),
                    edges =
                        mapOf(
                            EdgeId("e1") to EdgeRoute.Direct(source = p(20f, 20f), target = p(20f, 500f)),
                            EdgeId("e2") to EdgeRoute.Direct(source = p(20f + gap, 20f), target = p(20f + gap, 500f)),
                        ),
                    groups = emptyMap(),
                )
            return graph to result
        }

        test("two parallel edges closer than 16 px are a violation, 16 px is clean") {
            val (g1, r1) = twoEdgeGraph(gap = 15.9f)
            detectViolations(result = r1, graph = g1).parallel.size shouldBe 1
            val (g2, r2) = twoEdgeGraph(gap = 16f)
            detectViolations(result = r2, graph = g2).parallel.size shouldBe 0
            needsRelayout(result = r2, graph = g2) shouldBe false
        }

        test("bundled stubs of edges that share a node on one line are no violation") {
            val (g, r) = twoEdgeGraph(gap = 0f, shareNode = true)
            detectViolations(result = r, graph = g).parallel.size shouldBe 0
        }

        test("a T crossing and a short touching overlap are no parallel violation") {
            val a = p(0f, 0f) to p(0f, 100f)
            parallelGap(a, p(-20f, 50f) to p(20f, 50f)) shouldBe null
            (parallelOverlap(a, p(5f, 96f) to p(5f, 200f)) < PARALLEL_MIN_OVERLAP_PX) shouldBe true
        }

        test("an edge past a foreign node is a violation, the own nodes never count") {
            val (g, r) = twoEdgeGraph(gap = 100f)
            val near = r.copy(nodes = r.nodes + (NodeId("c") to NodeLayout(bounds = rect(25f, 200f, 40f, 20f))))
            // e1 (x = 20) passes 5 px from c; c is not an endpoint of e1
            val v = detectViolations(result = near, graph = g)
            v.edgeNode.any { it.edge == EdgeId("e1") && it.obstacle == "c" } shouldBe true
            v.edgeNode.none { it.edge == EdgeId("e1") && (it.obstacle == "a" || it.obstacle == "b") } shouldBe true
        }

        test("hostile input: more than 512 edges and 64 points per route are capped, not iterated") {
            val ids = (0 until 700).map { NodeId("n$it") }
            val nodes = ids.map { LayoutNode(id = it, intrinsicSize = Size(width = 10f, height = 10f)) }
            val edges =
                (0 until 699).map {
                    LayoutEdge(
                        id = EdgeId("e$it"),
                        source = EndpointRef(ids[it]),
                        target = EndpointRef(ids[it + 1]),
                    )
                }
            val long = (0 until 5000).map { p(it.toFloat(), (it % 2) * 3f) }
            val result =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 10f, height = 10f),
                    nodes = ids.associateWith { NodeLayout(bounds = rect(0f, 0f, 10f, 10f)) },
                    edges =
                        edges.associate {
                            it.id to
                                EdgeRoute.OrthogonalRounded(
                                    source = long.first(),
                                    target = long.last(),
                                    waypoints = long.drop(1).dropLast(1),
                                    cornerRadiusPx = 0f,
                                )
                        },
                    groups = emptyMap(),
                )
            val started = System.nanoTime()
            detectViolations(result = result, graph = LayoutGraph(nodes = nodes, edges = edges))
            ((System.nanoTime() - started) / 1_000_000_000L < 30L) shouldBe true
        }
    })
