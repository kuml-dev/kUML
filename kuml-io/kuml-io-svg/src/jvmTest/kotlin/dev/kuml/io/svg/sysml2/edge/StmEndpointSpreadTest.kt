package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class StmEndpointSpreadTest :
    FunSpec({
        val target = Rect(origin = Point(x = 100f, y = 200f), size = Size(width = 200f, height = 60f))

        /** An orthogonal route from above: down from (x, 0) to (x, 120), across to (tx, 120), down to the top side of [target]. */
        fun fromAbove(
            x: Float,
            tx: Float,
        ) = EdgeRoute.OrthogonalRounded(
            source = Point(x = x, y = 0f),
            waypoints = listOf(Point(x = x, y = 120f), Point(x = tx, y = 120f)),
            target = Point(x = tx, y = 200f),
            cornerRadiusPx = 8f,
        )

        fun points(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                else -> listOf(r.source, r.target)
            }

        fun orthogonal(r: EdgeRoute) = points(r).zipWithNext().all { (a, b) -> abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f }

        val ends = listOf(Triple("a", "s1", "T"), Triple("b", "s2", "T"), Triple("c", "s3", "T"))

        test("three ends on one side keep at least 16 px apart and stay orthogonal") {
            val routes = mapOf("a" to fromAbove(10f, 200f), "b" to fromAbove(50f, 204f), "c" to fromAbove(90f, 208f))
            val out = StmEndpointSpread.spread(routes = routes, ends = ends, boxes = mapOf("T" to target))
            val xs = listOf("a", "b", "c").map { out.getValue(it).target.x }.sorted()
            (xs[1] - xs[0] >= 16f - 0.01f) shouldBe true
            (xs[2] - xs[1] >= 16f - 0.01f) shouldBe true
            xs.all { it in 108f..292f } shouldBe true
            listOf("a", "b", "c").forEach { orthogonal(out.getValue(it)) shouldBe true }
        }

        test("ends that are far enough apart are left alone") {
            val routes = mapOf("a" to fromAbove(10f, 150f), "b" to fromAbove(50f, 200f), "c" to fromAbove(90f, 250f))
            StmEndpointSpread.spread(routes = routes, ends = ends, boxes = mapOf("T" to target)) shouldBe routes
        }

        test("pseudo-states are not handled: no box, no change") {
            val routes = mapOf("a" to fromAbove(10f, 200f), "b" to fromAbove(50f, 204f), "c" to fromAbove(90f, 208f))
            StmEndpointSpread.spread(routes = routes, ends = ends, boxes = emptyMap()) shouldBe routes
        }

        test("deterministic") {
            val routes = mapOf("a" to fromAbove(10f, 200f), "b" to fromAbove(50f, 204f), "c" to fromAbove(90f, 208f))
            val one = StmEndpointSpread.spread(routes = routes, ends = ends, boxes = mapOf("T" to target))
            StmEndpointSpread.spread(routes = routes, ends = ends, boxes = mapOf("T" to target)) shouldBe one
        }

        test("a straight vertical route gets a jog instead of a diagonal") {
            val direct = { x: Float -> EdgeRoute.Direct(source = Point(x = x, y = 0f), target = Point(x = x, y = 200f)) }
            val routes = mapOf("a" to direct(200f), "b" to direct(204f))
            val out = StmEndpointSpread.spread(routes = routes, ends = ends.take(2), boxes = mapOf("T" to target))
            val gap = abs(out.getValue("a").target.x - out.getValue("b").target.x)
            (gap >= 16f - 0.01f) shouldBe true
            listOf("a", "b").forEach { orthogonal(out.getValue(it)) shouldBe true }
        }

        test("side too short for the gap: ends are distributed over the side") {
            val narrow = Rect(origin = Point(x = 100f, y = 200f), size = Size(width = 50f, height = 60f))
            val routes = mapOf("a" to fromAbove(10f, 120f), "b" to fromAbove(50f, 124f), "c" to fromAbove(90f, 128f))
            val out = StmEndpointSpread.spread(routes = routes, ends = ends, boxes = mapOf("T" to narrow))
            listOf("a", "b", "c").forEach { (out.getValue(it).target.x in 108f..142f) shouldBe true }
        }
    })
