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
import io.kotest.matchers.shouldBe

/** PAL wave J: the lane spread moves close parallel edges apart and leaves everything else untouched. */
class ParallelLaneSpreadTest :
    FunSpec({
        timeout = 30_000L

        fun scene(
            nodeWidth: Float,
            xa: Float,
            xb: Float,
        ): Pair<LayoutGraph, LayoutResult> {
            val a = NodeId("a")
            val b = NodeId("b")
            val nodes = listOf(a, b).map { LayoutNode(id = it, intrinsicSize = Size(width = nodeWidth, height = 40f)) }
            val edges =
                listOf(
                    LayoutEdge(id = EdgeId("e1"), source = EndpointRef(a), target = EndpointRef(b)),
                    LayoutEdge(id = EdgeId("e2"), source = EndpointRef(b), target = EndpointRef(a)),
                )
            val result =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 400f, height = 400f),
                    nodes =
                        mapOf(
                            a to NodeLayout(bounds = Rect(origin = Point(100f, 0f), size = Size(width = nodeWidth, height = 40f))),
                            b to NodeLayout(bounds = Rect(origin = Point(100f, 200f), size = Size(width = nodeWidth, height = 40f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("e1") to EdgeRoute.Direct(source = Point(xa, 40f), target = Point(xa, 200f)),
                            EdgeId("e2") to EdgeRoute.Direct(source = Point(xb, 200f), target = Point(xb, 40f)),
                        ),
                    groups = emptyMap(),
                )
            return LayoutGraph(nodes = nodes, edges = edges) to result
        }

        test("two edges 8 px apart between wide nodes are spread to at least 16 px, ends stay on the node sides") {
            val (g, r) = scene(nodeWidth = 200f, xa = 196f, xb = 204f)
            val out = r.spreadParallelEdges(graph = g)
            val x1 = (out.edges.getValue(EdgeId("e1")) as EdgeRoute.Direct)
            val x2 = (out.edges.getValue(EdgeId("e2")) as EdgeRoute.Direct)
            (kotlin.math.abs(x1.source.x - x2.source.x) >= EDGE_EDGE_TARGET_GAP_PX) shouldBe true
            (x1.source.x == x1.target.x) shouldBe true
            detectViolations(result = out, graph = g).count shouldBe 0
        }

        test("a layout without violation is returned unchanged") {
            val (g, r) = scene(nodeWidth = 200f, xa = 150f, xb = 250f)
            r.spreadParallelEdges(graph = g) shouldBe r
        }

        test("nodes too narrow for two lanes leave the pair as it is") {
            val (g, r) = scene(nodeWidth = 20f, xa = 106f, xb = 114f)
            r.spreadParallelEdges(graph = g) shouldBe r
            detectViolations(result = r, graph = g).parallel shouldBe emptyList()
        }
    })
