@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.HeaderTextWidths
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * PAL wave P: frame-clearance detector (F1 along a frame side, F2 inside the header band) and the standoff pass, on
 * synthetic geometry without ELK. Only geometry, no names.
 */
class StmFrameStandoffTest :
    FunSpec({
        timeout = 30_000L

        fun p(
            x: Float,
            y: Float,
        ) = Point(x = x, y = y)

        // frame: x 100..500, y 200..500, header band 200..240 (padding.top 40, no side-entry gap)
        val frame = Rect(origin = p(100f, 200f), size = Size(width = 400f, height = 300f))

        fun fixture(routes: List<List<Point>>): Pair<LayoutGraph, LayoutResult> {
            val g =
                LayoutGroup(
                    id = GroupId("g"),
                    padding = Insets(top = 40f, right = 12f, bottom = 12f, left = 12f),
                    headerTextWidths = HeaderTextWidths(title = 40f, actions = 0f),
                )
            val edges =
                routes.indices.map {
                    LayoutEdge(id = EdgeId("e$it"), source = EndpointRef(NodeId("s")), target = EndpointRef(NodeId("t")))
                }
            val nodes =
                listOf(
                    LayoutNode(id = NodeId("s"), intrinsicSize = Size(width = 40f, height = 20f)),
                    LayoutNode(id = NodeId("t"), intrinsicSize = Size(width = 40f, height = 20f)),
                )
            val graph = LayoutGraph(nodes = nodes, edges = edges, groups = listOf(g))
            val result =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 1000f, height = 1000f),
                    nodes =
                        mapOf(
                            NodeId("s") to NodeLayout(bounds = Rect(origin = p(0f, 0f), size = Size(width = 40f, height = 20f))),
                            NodeId("t") to NodeLayout(bounds = Rect(origin = p(300f, 300f), size = Size(width = 40f, height = 20f))),
                        ),
                    edges =
                        routes
                            .mapIndexed { i, pts ->
                                EdgeId("e$i") to
                                    EdgeRoute.OrthogonalRounded(
                                        source = pts.first(),
                                        target = pts.last(),
                                        waypoints = pts.drop(1).dropLast(1),
                                        cornerRadiusPx = 6f,
                                    )
                            }.toMap(),
                    groups = mapOf(GroupId("g") to GroupLayout(bounds = frame)),
                )
            return graph to result
        }

        fun pts(
            r: LayoutResult,
            i: Int,
        ) = polylinePoints(r.edges.getValue(EdgeId("e$i")))!!

        // a route that comes down at x=170, runs along the top side at the given y and then enters the frame at x=60..
        fun hugging(
            xl: Float,
            xr: Float,
            y: Float,
        ) = listOf(p(xl, 20f), p(xl, y), p(xr, y), p(xr, 300f), p(xr + 10f, 300f))

        test("F1 measures the gap to a frame side and respects the 12 px boundary") {
            fun f1At(y: Float) = fixture(listOf(hugging(xl = 150f, xr = 400f, y = y))).let { (g, r) -> detectFrameViolations(r, g).f1 }
            f1At(189f) shouldBe 1 // 11 px above the top side
            f1At(188f) shouldBe 0 // exactly 12 px
            f1At(200f) shouldBe 1 // on the line
            f1At(150f) shouldBe 0
        }

        test("F1 is also counted inside the frame, parallel to a vertical side") {
            val route = listOf(p(300f, 260f), p(300f, 270f), p(106f, 270f), p(106f, 450f), p(300f, 450f), p(300f, 470f))
            val (g, r) = fixture(listOf(route))
            detectFrameViolations(r, g).f1 shouldBe 1
        }

        test("F2 counts horizontal runs inside the header band but not a vertical piercing") {
            val run = listOf(p(150f, 20f), p(150f, 220f), p(400f, 220f), p(400f, 300f), p(410f, 300f))
            val pierce = listOf(p(300f, 20f), p(300f, 100f), p(300f, 260f), p(310f, 260f))
            val (g, r) = fixture(listOf(run, pierce))
            val v = detectFrameViolations(r, g)
            v.f2 shouldBe 1
            v.headerRun.single().edge shouldBe EdgeId("e0")
        }

        test("a route that stays clear of the frame has no violation, and is returned as the same instance") {
            val (g, r) = fixture(listOf(hugging(xl = 150f, xr = 400f, y = 150f)))
            detectFrameViolations(r, g).f1 shouldBe 0
            (StmFrameStandoff.apply(result = r, graph = g) === r) shouldBe true
        }

        test("the standoff moves a hugging segment to the 16 px lane and keeps the end points") {
            val (g, r) = fixture(listOf(hugging(xl = 150f, xr = 400f, y = 196f)))
            val out = StmFrameStandoff.apply(result = r, graph = g)
            val moved = pts(out, 0)
            moved[1].y shouldBe (184f plusOrMinus 0.01f)
            moved[2].y shouldBe (184f plusOrMinus 0.01f)
            moved.first() shouldBe pts(r, 0).first()
            moved.last() shouldBe pts(r, 0).last()
            // every segment stays axis-parallel
            moved.zipWithNext().all { (a, b) -> a.x == b.x || a.y == b.y } shouldBe true
            detectFrameViolations(out, g).f1 shouldBe 0
        }

        test("three hugging edges end up on three lanes 16 px apart without a crossing") {
            val routes = listOf(hugging(150f, 400f, 196f), hugging(200f, 420f, 196f), hugging(250f, 450f, 196f))
            val (g, r) = fixture(routes)
            detectFrameViolations(r, g).f1 shouldBe 3
            val out = StmFrameStandoff.apply(result = r, graph = g)
            detectFrameViolations(out, g).f1 shouldBe 0
            countCrossings(out.edges.values.mapNotNull { polylinePoints(it) }) shouldBe 0
            val lanes = (0..2).map { pts(out, it)[1].y }.sorted()
            lanes.zip(listOf(152f, 168f, 184f)).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.01f) }
        }

        test("a vertical hugging segment is moved to the outer lane on the left side") {
            val route = listOf(p(20f, 100f), p(20f, 190f), p(90f, 190f), p(90f, 300f), p(110f, 300f))
            val (g, r) = fixture(listOf(route))
            detectFrameViolations(r, g).f1 shouldBe 1
            val out = StmFrameStandoff.apply(result = r, graph = g)
            pts(out, 0)[2].x shouldBe (84f plusOrMinus 0.01f)
            detectFrameViolations(out, g).f1 shouldBe 0
        }

        test("a move that would leave the canvas on the lower bound is vetoed and the result is unchanged") {
            // frame at x = 10: the lane 16 px to its left would be at x = -6
            val route = listOf(p(60f, 250f), p(5f, 250f), p(5f, 400f), p(60f, 400f), p(60f, 420f))
            val (g, r0) = fixture(listOf(route))
            val r =
                r0.copy(
                    groups =
                        mapOf(
                            GroupId("g") to GroupLayout(bounds = Rect(origin = p(10f, 200f), size = Size(width = 400f, height = 300f))),
                        ),
                )
            detectFrameViolations(r, g).f1 shouldBe 1
            (StmFrameStandoff.apply(result = r, graph = g) === r) shouldBe true
        }

        test("a move that would leave the canvas on the right edge is vetoed") {
            // frame right = 500, canvas width 510: the lane at x = 516 is off canvas
            val route = listOf(p(450f, 250f), p(505f, 250f), p(505f, 400f), p(450f, 400f), p(450f, 420f))
            val (g, r0) = fixture(listOf(route))
            val r = r0.copy(canvas = Size(width = 510f, height = 1000f))
            detectFrameViolations(r, g).f1 shouldBe 1
            (StmFrameStandoff.apply(result = r, graph = g) === r) shouldBe true
        }

        test("a move that would leave the canvas on the bottom edge is vetoed") {
            val route = listOf(p(200f, 450f), p(200f, 505f), p(400f, 505f), p(400f, 450f), p(410f, 450f))
            val (g, r0) = fixture(listOf(route))
            val r = r0.copy(canvas = Size(width = 1000f, height = 510f))
            detectFrameViolations(r, g).f1 shouldBe 1
            (StmFrameStandoff.apply(result = r, graph = g) === r) shouldBe true
        }

        test("a move that would add a crossing is vetoed, while the same move without the blocker happens") {
            val hug = hugging(xl = 150f, xr = 400f, y = 196f)
            // without blocker the segment is moved up to y = 184
            val (g0, r0) = fixture(listOf(hug))
            pts(StmFrameStandoff.apply(result = r0, graph = g0), 0)[1].y shouldBe (184f plusOrMinus 0.01f)
            // a second edge crosses the corridor: the move up to y = 184 would cut its vertical at x = 300
            val blocker = listOf(p(250f, 150f), p(250f, 170f), p(300f, 170f), p(300f, 190f), p(310f, 190f))
            val (g, r) = fixture(listOf(hug, blocker))
            val before = countCrossings(r.edges.values.mapNotNull { polylinePoints(it) })
            val out = StmFrameStandoff.apply(result = r, graph = g)
            countCrossings(out.edges.values.mapNotNull { polylinePoints(it) }) shouldBe before
            pts(out, 0) shouldBe pts(r, 0)
        }

        test("hostile input: more than the cap of hugging segments leaves the layout unchanged and the pass is deterministic") {
            val routes = (0 until 20).map { hugging(xl = 110f + it, xr = 480f - it, y = 196f) }
            val (g, r) = fixture(routes)
            val a = StmFrameStandoff.apply(result = r, graph = g)
            val b = StmFrameStandoff.apply(result = r, graph = g)
            a shouldBe b
            (a === r) shouldBe true
        }
    })
