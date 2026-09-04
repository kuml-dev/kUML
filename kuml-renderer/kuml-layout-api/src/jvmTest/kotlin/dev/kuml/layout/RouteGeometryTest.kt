package dev.kuml.layout

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * [RouteGeometry.kt] is the single shared geometry kernel behind the
 * [UmlAssociationClass][dev.kuml.uml.UmlAssociationClass] tether — see the
 * class-level KDoc on [EdgeRoute.arcLengthMidpoint] for why arc length
 * (rather than the middle waypoint by index) is the deliberate design.
 */
class RouteGeometryTest :
    FunSpec({

        val eps = 0.01f

        // ── arcLengthMidpoint ─────────────────────────────────────────────────

        test("arcLengthMidpoint on a Direct route is the exact midpoint") {
            val route = EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 100f, y = 0f))
            val mid = route.arcLengthMidpoint()
            mid.x shouldBe (50f plusOrMinus eps)
            mid.y shouldBe (0f plusOrMinus eps)
        }

        test("arcLengthMidpoint on a route with unequal segments lands on the long segment, not the middle waypoint") {
            // source -> wp (100 px vertical run) -> target (10 px horizontal jog).
            // Total length 110, half-length 55 — falls 55px into the first (100px)
            // segment, nowhere near the waypoint at (0, 100), which an
            // index-based "middle point" pick would wrongly return.
            val source = Point(x = 0f, y = 0f)
            val waypoint = Point(x = 0f, y = 100f)
            val target = Point(x = 10f, y = 100f)
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = source,
                    target = target,
                    waypoints = listOf(waypoint),
                    cornerRadiusPx = 4f,
                )
            val mid = route.arcLengthMidpoint()
            // Expected: 55 px along the first (vertical) segment.
            mid.x shouldBe (0f plusOrMinus eps)
            mid.y shouldBe (55f plusOrMinus eps)
            // Regression guard against the index-middle-waypoint bug: the
            // waypoint itself is at y=100, well past the true arc-length midpoint.
            (mid.y < waypoint.y) shouldBe true
        }

        test("arcLengthMidpoint on a Bezier route walks the control-point polygon") {
            val route =
                EdgeRoute.Bezier(
                    source = Point(x = 0f, y = 0f),
                    target = Point(x = 0f, y = 20f),
                    controlPoints = listOf(Point(x = 0f, y = 10f)),
                )
            val mid = route.arcLengthMidpoint()
            mid.x shouldBe (0f plusOrMinus eps)
            mid.y shouldBe (10f plusOrMinus eps)
        }

        test("arcLengthMidpoint on a degenerate zero-length route returns the source") {
            val p = Point(x = 5f, y = 5f)
            val route = EdgeRoute.Direct(source = p, target = p)
            route.arcLengthMidpoint() shouldBe p
        }

        // ── borderIntersectionTowards ─────────────────────────────────────────

        val box = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 100f, height = 50f))

        test("borderIntersectionTowards a point directly above the box hits the top edge") {
            val hit = box.borderIntersectionTowards(from = Point(x = 50f, y = -1000f))
            hit.x shouldBe (50f plusOrMinus eps)
            hit.y shouldBe (0f plusOrMinus eps)
        }

        test("borderIntersectionTowards a point directly below the box hits the bottom edge") {
            val hit = box.borderIntersectionTowards(from = Point(x = 50f, y = 1000f))
            hit.x shouldBe (50f plusOrMinus eps)
            hit.y shouldBe (50f plusOrMinus eps)
        }

        test("borderIntersectionTowards a point directly left of the box hits the left edge") {
            val hit = box.borderIntersectionTowards(from = Point(x = -1000f, y = 25f))
            hit.x shouldBe (0f plusOrMinus eps)
            hit.y shouldBe (25f plusOrMinus eps)
        }

        test("borderIntersectionTowards a point directly right of the box hits the right edge") {
            val hit = box.borderIntersectionTowards(from = Point(x = 1000f, y = 25f))
            hit.x shouldBe (100f plusOrMinus eps)
            hit.y shouldBe (25f plusOrMinus eps)
        }

        test("borderIntersectionTowards a point beyond the diagonal corner hits a border point, not the corner interior") {
            // Far beyond the top-right corner along the diagonal.
            val hit = box.borderIntersectionTowards(from = Point(x = 1000f, y = -1000f))
            // Must land exactly on the border (either the right or the top edge).
            val onRightEdge = kotlin.math.abs(hit.x - 100f) < eps
            val onTopEdge = kotlin.math.abs(hit.y - 0f) < eps
            (onRightEdge || onTopEdge) shouldBe true
        }

        test("borderIntersectionTowards a point inside the box returns the center") {
            val hit = box.borderIntersectionTowards(from = Point(x = 60f, y = 30f))
            hit.x shouldBe (50f plusOrMinus eps)
            hit.y shouldBe (25f plusOrMinus eps)
        }

        test("borderIntersectionTowards the exact center returns the center") {
            val center = Point(x = 50f, y = 25f)
            val hit = box.borderIntersectionTowards(from = center)
            hit shouldBe center
        }
    })
