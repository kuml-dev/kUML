package dev.kuml.io.svg.sysml2

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.PinSide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class Sysml2C2bUnitTest :
    FunSpec({
        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        test("clipper passes rectangle end points through unchanged") {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 10f, y = 0f),
                    target = Point(x = 10f, y = 100f),
                    waypoints = listOf(Point(x = 10f, y = 30f), Point(x = 40f, y = 60f)),
                    cornerRadiusPx = 4f,
                )
            val shape = Sysml2ActivityEdgeClipper.Shape.Rectangle(rect(0f, 100f, 80f, 40f))
            val out = Sysml2ActivityEdgeClipper.clip(route = route, sourceShape = null, targetShape = shape)
            out.target shouldBe route.target
        }

        test("diamond keeps the cross coordinate of an axis-parallel segment") {
            val d = Sysml2ActivityEdgeClipper.Shape.Diamond(rect(0f, 0f, 40f, 40f))
            val p =
                Sysml2ActivityEdgeClipper.axisAligned(
                    shape = d,
                    original = Point(x = 0f, y = 10f),
                    neighbor = Point(x = -50f, y = 10f),
                )!!
            p.y shouldBe 10f
            // diamond border at y offset -10: halfW * (1 - 10/20) = 10 left of the centre x=20
            p.x shouldBe 10f
        }

        test("circle snaps along a vertical segment") {
            val c = Sysml2ActivityEdgeClipper.Shape.Circle(bounds = rect(0f, 0f, 28f, 28f), radiusPx = 12f)
            val p =
                Sysml2ActivityEdgeClipper.axisAligned(
                    shape = c,
                    original = Point(x = 14f, y = 0f),
                    neighbor = Point(x = 14f, y = -40f),
                )!!
            p.x shouldBe 14f
            p.y shouldBe 2f
        }

        test("fork anchors are spread evenly and at least 16 px apart on a sized bar") {
            val bar = rect(0f, 0f, Sysml2ForkJoinAnchors.requiredWidth(3), 10f)
            val xs =
                Sysml2ForkJoinAnchors
                    .distribute(
                        bar = bar,
                        arriving =
                            listOf(
                                BarAttachment(edgeId = "a", atTarget = true, neighborX = 5f),
                                BarAttachment(edgeId = "b", atTarget = true, neighborX = 1f),
                                BarAttachment(edgeId = "c", atTarget = true, neighborX = 9f),
                            ),
                        leaving = emptyList(),
                    )
            xs.getValue("b" to true) shouldBe 16f
            xs.getValue("a" to true) shouldBe 32f
            xs.getValue("c" to true) shouldBe 48f
            (xs.getValue("a" to true) - xs.getValue("b" to true)).shouldBeGreaterThanOrEqual(16f)
        }

        test("placeFrameLabel: free band keeps the label left") {
            val p = placeFrameLabel(frame = rect(0f, 0f, 300f, 200f), labelW = 80f, bandH = 20f, edges = emptyList())
            p.x shouldBe 0f
            p.fallbackOccluded shouldBe false
        }

        test("placeFrameLabel: an edge through the left spot moves the label into the next gap") {
            val edge = listOf(Point(x = 40f, y = -10f), Point(x = 40f, y = 100f))
            val p = placeFrameLabel(frame = rect(0f, 0f, 400f, 200f), labelW = 80f, bandH = 20f, edges = listOf(edge))
            p.fallbackOccluded shouldBe false
            (p.x > 40f) shouldBe true
        }

        test("placeFrameLabel: the label finds the only free gap at the right") {
            val edges = (0..10).map { listOf(Point(x = it * 20f + 2f, y = -10f), Point(x = it * 20f + 2f, y = 100f)) }
            val p = placeFrameLabel(frame = rect(0f, 0f, 300f, 200f), labelW = 80f, bandH = 20f, edges = edges)
            p.fallbackOccluded shouldBe false
            (p.x > 208f) shouldBe true
            (p.x + 80f <= 300f) shouldBe true
        }

        test("placeFrameLabel: fully occluded band reports the fallback") {
            val edges = (0..30).map { listOf(Point(x = it * 10f, y = -10f), Point(x = it * 10f, y = 100f)) }
            val p = placeFrameLabel(frame = rect(0f, 0f, 300f, 200f), labelW = 80f, bandH = 20f, edges = edges)
            p.fallbackOccluded shouldBe true
            p.x shouldBe 0f
        }

        test("orthogonalize: diagonal pin edge becomes axis parallel with perpendicular stubs") {
            val route = EdgeRoute.Direct(source = Point(x = 100f, y = 100f), target = Point(x = 40f, y = 200f))
            val out =
                Sysml2PinRouting.orthogonalize(
                    route = route,
                    sourceSide = PinSide.Bottom,
                    targetSide = PinSide.Top,
                ) as EdgeRoute.OrthogonalRounded
            val pts = listOf(out.source) + out.waypoints + listOf(out.target)
            for (i in 0 until pts.size - 1) (abs(pts[i + 1].x - pts[i].x) <= 0.5f || abs(pts[i + 1].y - pts[i].y) <= 0.5f) shouldBe true
            // first segment leaves downward, last arrives downward, both at least the stub length
            abs(pts[1].x - pts[0].x) shouldBe 0f
            (pts[1].y - pts[0].y).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
            abs(pts.last().x - pts[pts.size - 2].x) shouldBe 0f
            (pts.last().y - pts[pts.size - 2].y).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
        }

        test("orthogonalize: L bend for vertical exit and horizontal entry") {
            val route = EdgeRoute.Direct(source = Point(x = 100f, y = 100f), target = Point(x = 300f, y = 200f))
            val out =
                Sysml2PinRouting.orthogonalize(
                    route = route,
                    sourceSide = PinSide.Bottom,
                    targetSide = PinSide.Left,
                ) as EdgeRoute.OrthogonalRounded
            val pts = listOf(out.source) + out.waypoints + listOf(out.target)
            for (i in 0 until pts.size - 1) (abs(pts[i + 1].x - pts[i].x) <= 0.5f || abs(pts[i + 1].y - pts[i].y) <= 0.5f) shouldBe true
            (pts.last().x - pts[pts.size - 2].x).shouldBeGreaterThanOrEqual(12f)
        }

        test("orthogonalize: already orthogonal perpendicular route stays unchanged") {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 50f, y = 60f),
                    target = Point(x = 50f, y = 160f),
                    waypoints = emptyList(),
                    cornerRadiusPx = 4f,
                )
            Sysml2PinRouting.orthogonalize(route = route, sourceSide = PinSide.Bottom, targetSide = PinSide.Top) shouldBe route
        }

        test("orthogonalize: no pin at either end leaves the route alone") {
            val route = EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 30f, y = 40f))
            Sysml2PinRouting.orthogonalize(route = route, sourceSide = null, targetSide = null) shouldBe route
        }
    })
