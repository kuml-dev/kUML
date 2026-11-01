package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** Wave 2E-a - endpoint spread and parallel segment nudging of requirement diagram routes. */
class ReqEdgeBandsTest :
    FunSpec({
        fun route(vararg p: Point): EdgeRoute =
            EdgeRoute.OrthogonalRounded(source = p.first(), target = p.last(), waypoints = p.drop(1).dropLast(1), cornerRadiusPx = 6f)

        fun isOrthogonal(r: EdgeRoute): Boolean =
            r.polylinePoints().zipWithNext().all { (a, b) -> abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f }

        val canvas = Size(width = 800f, height = 600f)

        // Two cards far left and far right, two edges between them that share the corridor at y = 300 / 302.
        val left = Rect(origin = Point(x = 0f, y = 280f), size = Size(width = 100f, height = 60f))
        val right = Rect(origin = Point(x = 500f, y = 100f), size = Size(width = 100f, height = 60f))
        val ends = listOf(Triple("a", "L", "R"), Triple("b", "L", "R"))
        val boxes = mapOf("L" to left, "R" to right)

        fun twoParallel(): Map<String, EdgeRoute> =
            mapOf(
                "a" to route(Point(x = 100f, y = 300f), Point(x = 140f, y = 300f), Point(x = 140f, y = 120f), Point(x = 500f, y = 120f)),
                "b" to route(Point(x = 100f, y = 320f), Point(x = 160f, y = 320f), Point(x = 160f, y = 122f), Point(x = 500f, y = 122f)),
            )

        test("nudge: two overlapping segments two pixels apart are moved to at least a 10 px pitch and stay orthogonal") {
            val out = ReqEdgeBands.nudge(routes = twoParallel(), ends = ends, boxes = boxes, canvas = canvas)
            val ya =
                out
                    .getValue("a")
                    .polylinePoints()
                    .last()
                    .y
            val yb =
                out
                    .getValue("b")
                    .polylinePoints()
                    .last()
                    .y
            // the last segments end on the card, the horizontal middle segments were separated
            val ha =
                out
                    .getValue("a")
                    .polylinePoints()
                    .filter { it.x > 400f }
                    .map { it.y }
                    .distinct()
            val hb =
                out
                    .getValue("b")
                    .polylinePoints()
                    .filter { it.x > 400f }
                    .map { it.y }
                    .distinct()
            out.values.forEach { isOrthogonal(it) shouldBe true }
            (ya.isFinite() && yb.isFinite()) shouldBe true
            (ha.isNotEmpty() && hb.isNotEmpty()) shouldBe true
            (abs(ha.first() - hb.first()) >= 9.99f) shouldBe true
        }

        test("nudge: deterministic, and a route that is already clear is returned unchanged") {
            val first = ReqEdgeBands.nudge(routes = twoParallel(), ends = ends, boxes = boxes, canvas = canvas)
            val second = ReqEdgeBands.nudge(routes = twoParallel(), ends = ends, boxes = boxes, canvas = canvas)
            first shouldBe second
            val clear =
                mapOf(
                    "a" to
                        route(
                            Point(x = 100f, y = 300f),
                            Point(x = 140f, y = 300f),
                            Point(x = 140f, y = 120f),
                            Point(x = 500f, y = 120f),
                        ),
                    "b" to
                        route(
                            Point(x = 100f, y = 320f),
                            Point(x = 160f, y = 320f),
                            Point(x = 160f, y = 220f),
                            Point(x = 500f, y = 220f),
                        ),
                )
            ReqEdgeBands.nudge(routes = clear, ends = ends, boxes = boxes, canvas = canvas) shouldBe clear
        }

        test("nudge: a move that would run into a card is skipped") {
            val wall = Rect(origin = Point(x = 300f, y = 80f), size = Size(width = 100f, height = 70f))
            val blocked = boxes + mapOf("W" to wall)
            val a = route(Point(x = 100f, y = 300f), Point(x = 140f, y = 300f), Point(x = 140f, y = 160f), Point(x = 500f, y = 160f))
            val b = route(Point(x = 100f, y = 320f), Point(x = 160f, y = 320f), Point(x = 160f, y = 162f), Point(x = 500f, y = 162f))
            val out = ReqEdgeBands.nudge(routes = mapOf("a" to a, "b" to b), ends = ends, boxes = blocked, canvas = canvas)
            for (r in out.values) {
                r.polylinePoints().zipWithNext().forEach { (p, q) ->
                    val hitsWall =
                        maxOf(p.x, q.x) > wall.origin.x + 1f &&
                            minOf(p.x, q.x) < wall.origin.x + wall.size.width - 1f &&
                            maxOf(p.y, q.y) > wall.origin.y + 1f &&
                            minOf(p.y, q.y) < wall.origin.y + wall.size.height - 1f
                    hitsWall shouldBe false
                }
            }
        }

        test("spreadEndpoints: five ends on one side are at least 12 px apart and 8 px from the corners") {
            val box = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 100f, height = 200f))
            val routes =
                (0 until 5).associate { i ->
                    "e$i" to
                        route(
                            Point(x = 100f, y = 98f + i * 1f),
                            Point(x = 140f, y = 98f + i * 1f),
                            Point(x = 140f, y = 300f + i * 20f),
                            Point(x = 300f, y = 300f + i * 20f),
                        )
                }
            val e = routes.keys.map { Triple(it, "B", "T") }
            val out =
                ReqEdgeBands.spreadEndpoints(
                    routes = routes,
                    ends = e,
                    boxes =
                        mapOf(
                            "B" to box,
                            "T" to Rect(origin = Point(x = 300f, y = 280f), size = Size(width = 100f, height = 200f)),
                        ),
                )
            val ys = out.values.map { it.polylinePoints().first().y }.sorted()
            ys.zipWithNext().all { (a, b) -> b - a >= 11.99f } shouldBe true
            (ys.first() >= 8f && ys.last() <= 192f) shouldBe true
            out.values.forEach { isOrthogonal(it) shouldBe true }
        }
    })
