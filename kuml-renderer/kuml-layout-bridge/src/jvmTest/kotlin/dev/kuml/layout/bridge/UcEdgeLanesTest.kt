package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private fun p(
    x: Float,
    y: Float,
) = Point(x = x, y = y)

private fun route(vararg pts: Point): EdgeRoute.OrthogonalRounded =
    EdgeRoute.OrthogonalRounded(source = pts.first(), target = pts.last(), waypoints = pts.drop(1).dropLast(1), cornerRadiusPx = 8f)

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

class UcEdgeLanesTest :
    FunSpec({
        val none = emptyMap<EdgeId, Pair<NodeId, NodeId>>()

        fun xsOfVerticals(r: EdgeRoute): List<Float> {
            val pts = r.polylinePoints()
            return (0 until pts.size - 1).filter { abs(pts[it].x - pts[it + 1].x) < 0.01f }.map { pts[it].x }
        }

        fun cluster(order: List<Int>): Map<EdgeId, EdgeRoute> {
            // three edges whose vertical corridor is the same x = 500 and which overlap in y
            val all =
                mapOf(
                    1 to
                        route(
                            p(x = 400f, y = 100f),
                            p(x = 412f, y = 100f),
                            p(x = 500f, y = 100f),
                            p(x = 500f, y = 400f),
                            p(x = 500f, y = 400f),
                            p(x = 700f, y = 400f),
                        ),
                    2 to route(p(x = 400f, y = 120f), p(x = 500f, y = 120f), p(x = 500f, y = 420f), p(x = 700f, y = 420f)),
                    3 to route(p(x = 400f, y = 140f), p(x = 500f, y = 140f), p(x = 500f, y = 440f), p(x = 700f, y = 440f)),
                )
            return order.associate { EdgeId("e$it") to all.getValue(it) }
        }

        test("collinear overlapping segments are moved to 12 px lanes") {
            val out = UcEdgeLanes.plan(routes = cluster(listOf(1, 2, 3)), ends = none, nodes = emptyMap()).routes
            val xs = out.values.map { xsOfVerticals(it).single() }.sorted()
            (xs[1] - xs[0]) shouldBeGreaterThanOrEqualTo 12f - 0.01f
            (xs[2] - xs[1]) shouldBeGreaterThanOrEqualTo 12f - 0.01f
        }

        test("the result does not depend on the order of the edges") {
            val a = UcEdgeLanes.plan(routes = cluster(listOf(1, 2, 3)), ends = none, nodes = emptyMap()).routes
            val b = UcEdgeLanes.plan(routes = cluster(listOf(3, 1, 2)), ends = none, nodes = emptyMap()).routes
            for (id in a.keys) a.getValue(id).polylinePoints() shouldBe b.getValue(id).polylinePoints()
        }

        test("first and last segments stay on their port") {
            val inRoutes = cluster(listOf(1, 2, 3))
            val out = UcEdgeLanes.plan(routes = inRoutes, ends = none, nodes = emptyMap()).routes
            for ((id, r) in out) {
                r.polylinePoints().first() shouldBe inRoutes.getValue(id).polylinePoints().first()
                r.polylinePoints().last() shouldBe inRoutes.getValue(id).polylinePoints().last()
            }
        }

        test("an obstacle beside the corridor forces the lane to the other side") {
            val wall = rect(x = 505f, y = 90f, w = 60f, h = 400f) // right of x = 500
            val nodes = mapOf(NodeId("wall") to wall)
            val out = UcEdgeLanes.plan(routes = cluster(listOf(1, 2, 3)), ends = none, nodes = nodes).routes
            val xs = out.values.map { xsOfVerticals(it).single() }
            xs.forEach { (it <= 505f - 0.5f) shouldBe true }
        }

        test("a micro jog is collapsed onto the port line") {
            // A first segment at y = 100 (port), a 6 px jog, a track at y = 106 and a last segment at y = 300 (second port)
            val r =
                route(
                    p(x = 400f, y = 100f),
                    p(x = 450f, y = 100f),
                    p(x = 450f, y = 106f),
                    p(x = 600f, y = 106f),
                    p(x = 600f, y = 300f),
                    p(x = 700f, y = 300f),
                )
            val res = UcEdgeLanes.plan(routes = mapOf(EdgeId("j") to r), ends = none, nodes = emptyMap())
            res.stats.collapsed shouldBe 1
            val pts = res.routes.getValue(EdgeId("j")).polylinePoints()
            pts.map { it.y }.distinct().size shouldBe 2 // y = 100 and y = 300 only
        }

        test("an unavoidable jog between two fixed ports gets sharp corners instead of a bump") {
            val r = route(p(x = 400f, y = 100f), p(x = 450f, y = 100f), p(x = 450f, y = 108f), p(x = 700f, y = 108f))
            val out = UcEdgeLanes.plan(routes = mapOf(EdgeId("j") to r), ends = none, nodes = emptyMap()).routes.getValue(EdgeId("j"))
            (out as EdgeRoute.OrthogonalRounded).cornerRadiusPx shouldBe 0f
        }

        test("clipToEllipses moves a box-side end onto the outline along the last segment") {
            val uc = rect(x = 300f, y = 200f, w = 280f, h = 70f)
            val ends = mapOf(EdgeId("e") to (NodeId("A") to NodeId("UC")))
            // arrives from the left at y = 215 (20 px above the centre line 235), at the box side x = 300
            val r = route(p(x = 100f, y = 215f), p(x = 250f, y = 215f), p(x = 300f, y = 215f))
            val out = UcEdgeLanes.clipToEllipses(routes = mapOf(EdgeId("e") to r), useCases = mapOf(NodeId("UC") to uc), ends = ends)
            val end = out.getValue(EdgeId("e")).polylinePoints().last()
            end.y shouldBe 215f
            val e =
                dev.kuml.layout.bridge.note.AnchorGeometry
                    .Ellipse(cx = 440f, cy = 235f, rx = 140f, ry = 35f)
            dev.kuml.layout.bridge.note.EllipseGeometry
                .residual(e = e, p = end) shouldBeLessThanOrEqual 0.01f
            (end.x > 300f) shouldBe true
        }

        test("clipToEllipses leaves ends alone that are not at a use case") {
            val ends = mapOf(EdgeId("e") to (NodeId("A") to NodeId("B")))
            val r = route(p(x = 100f, y = 215f), p(x = 300f, y = 215f))
            val out = UcEdgeLanes.clipToEllipses(routes = mapOf(EdgeId("e") to r), useCases = emptyMap(), ends = ends)
            out.getValue(EdgeId("e")).polylinePoints() shouldBe r.polylinePoints()
        }

        test("too many segments skip the passes instead of running quadratic work") {
            val routes =
                (0 until 1100).associate {
                    EdgeId("e$it") to
                        route(p(x = 0f, y = it.toFloat()), p(x = 50f, y = it.toFloat()), p(x = 50f, y = it + 5f), p(x = 100f, y = it + 5f))
                }
            UcEdgeLanes.plan(routes = routes, ends = none, nodes = emptyMap()).stats.skipped shouldBe true
        }
    })
