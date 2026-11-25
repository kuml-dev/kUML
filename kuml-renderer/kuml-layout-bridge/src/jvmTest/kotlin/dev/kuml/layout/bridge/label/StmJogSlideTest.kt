@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private fun p(
    x: Float,
    y: Float,
) = Point(x = x, y = y)

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** PAL Welle O: the jog slide, geometry only. */
class StmJogSlideTest :
    FunSpec(body = {
        timeout = 60_000L

        // A vertical route from (100, 300) up to (113.26, 150): long segment, 13.26 px jog at y = 195, 45 px stub.
        val vertical =
            EdgeRoute.OrthogonalRounded(
                source = p(100f, 300f),
                target = p(113.26f, 150f),
                waypoints = listOf(p(100f, 195f), p(113.26f, 195f)),
                cornerRadiusPx = 0f,
            )

        fun waypointsOf(r: EdgeRoute): List<Point> = (r as EdgeRoute.OrthogonalRounded).waypoints

        val boxes = mapOf("S" to rect(0f, 300f, 300f, 40f), "T" to rect(0f, 110f, 300f, 40f))
        val ends = mapOf("e" to ("S" to "T"))

        fun accepts(
            candidate: EdgeRoute,
            others: Map<String, EdgeRoute> = emptyMap(),
            otherEnds: Map<String, Pair<String, String>> = emptyMap(),
            frames: List<Rect> = emptyList(),
        ) = StmJogSlide.acceptsGeometry(
            id = "e",
            candidate = candidate,
            routes = mapOf("e" to vertical) + others,
            ends = ends + otherEnds,
            nodes = boxes,
            frames = frames,
        )

        test("a vertical long / jog / long route gets two candidates: jog at the target end, then at the source end") {
            val c = StmJogSlide.candidates(vertical)
            c shouldHaveSize 2
            waypointsOf(c[0]).map { it.y } shouldBe listOf(162f, 162f)
            waypointsOf(c[1]).map { it.y } shouldBe listOf(288f, 288f)
            for (r in c) {
                r.source shouldBe vertical.source
                r.target shouldBe vertical.target
                waypointsOf(r).map { it.x } shouldBe listOf(100f, 113.26f)
            }
        }

        test("a horizontal flow is slid on x") {
            val horizontal =
                EdgeRoute.OrthogonalRounded(
                    source = p(300f, 100f),
                    target = p(150f, 113.26f),
                    waypoints = listOf(p(195f, 100f), p(195f, 113.26f)),
                    cornerRadiusPx = 0f,
                )
            val c = StmJogSlide.candidates(horizontal)
            c shouldHaveSize 2
            waypointsOf(c[0]).map { it.x } shouldBe listOf(162f, 162f)
            waypointsOf(c[0]).map { it.y } shouldBe listOf(100f, 113.26f)
            waypointsOf(c[1]).map { it.x } shouldBe listOf(288f, 288f)
        }

        test("every other shape has no candidate") {
            StmJogSlide.candidates(EdgeRoute.Direct(source = p(0f, 0f), target = p(0f, 100f))).shouldBeEmpty()
            StmJogSlide.candidates(vertical.copy(waypoints = listOf(p(100f, 195f)))).shouldBeEmpty()
            StmJogSlide.candidates(vertical.copy(waypoints = waypointsOf(vertical) + p(113.26f, 180f))).shouldBeEmpty()
            // A jog longer than MAX_JOG_PX is not a short jog.
            StmJogSlide.candidates(vertical.copy(target = p(140f, 150f), waypoints = listOf(p(100f, 195f), p(140f, 195f)))).shouldBeEmpty()
            // The channel is shorter than twice the inset.
            StmJogSlide
                .candidates(vertical.copy(source = p(100f, 170f), waypoints = listOf(p(100f, 160f), p(113.26f, 160f))))
                .shouldBeEmpty()
            // The jog lies outside the channel (the route turns back).
            StmJogSlide.candidates(vertical.copy(waypoints = listOf(p(100f, 320f), p(113.26f, 320f)))).shouldBeEmpty()
            // Not finite.
            StmJogSlide.candidates(vertical.copy(waypoints = listOf(p(100f, Float.NaN), p(113.26f, Float.NaN)))).shouldBeEmpty()
        }

        test("a jog of 24.75 px (Doc 55 T56) still qualifies") {
            val t56 =
                EdgeRoute.OrthogonalRounded(
                    source = p(578.59f, 561f),
                    target = p(553.84f, 696f),
                    waypoints = listOf(p(578.59f, 606f), p(553.84f, 606f)),
                    cornerRadiusPx = 0f,
                )
            StmJogSlide.candidates(t56) shouldHaveSize 2
        }

        test("a free channel accepts both candidates") {
            StmJogSlide.candidates(vertical).forEach { accepts(it) shouldBe true }
        }

        test("a foreign parallel segment closer than 16 px to the new jog rejects it") {
            val c = StmJogSlide.candidates(vertical)
            // A foreign horizontal edge 10 px above the target-end jog (y = 162).
            val foreign =
                mapOf(
                    "f" to
                        EdgeRoute.OrthogonalRounded(
                            source = p(60f, 152f),
                            target = p(160f, 152f),
                            waypoints = emptyList(),
                            cornerRadiusPx = 0f,
                        ),
                )
            accepts(c[0], foreign, mapOf("f" to ("A" to "B"))) shouldBe false
            // The source-end candidate is far from it.
            accepts(c[1], foreign, mapOf("f" to ("A" to "B"))) shouldBe true
        }

        test("a candidate that crosses a foreign route more often is rejected") {
            val c = StmJogSlide.candidates(vertical)
            // A vertical foreign edge at x = 106 between y 170..250: the original jog (y = 195, x 100..113.26) crosses it, the
            // target-end jog (y = 162) does not; here the opposite: a foreign edge spanning only the new jog.
            val onlyNew =
                mapOf(
                    "f" to
                        EdgeRoute.OrthogonalRounded(
                            source = p(106f, 155f),
                            target = p(106f, 170f),
                            waypoints = emptyList(),
                            cornerRadiusPx = 0f,
                        ),
                )
            accepts(c[0], onlyNew, mapOf("f" to ("A" to "B"))) shouldBe false
            accepts(c[1], onlyNew, mapOf("f" to ("A" to "B"))) shouldBe true
        }

        test("an end point of another route on the old route that is not on the new one rejects the candidate") {
            val c = StmJogSlide.candidates(vertical)
            // A note anchor that ends on the old jog.
            val anchor =
                mapOf("a" to EdgeRoute.Direct(source = p(106.6f, 195f), target = p(106.6f, 230f)))
            // The anchor line is vertical and ends on the horizontal old jog; every candidate moves the jog away.
            accepts(c[0], anchor, mapOf("a" to ("N" to "N2"))) shouldBe false
        }

        test("a node closer than 12 px to the new route rejects it") {
            // Left of the long segment: 8 px from the target-end candidate (its segment now reaches up to y = 162), 17 px from the old route.
            val near = boxes + ("N" to rect(50f, 160f, 42f, 20f))
            val c = StmJogSlide.candidates(vertical)
            StmJogSlide.acceptsGeometry(
                id = "e",
                candidate = c[0],
                routes = mapOf("e" to vertical),
                ends = ends,
                nodes = near,
                frames = emptyList(),
            ) shouldBe false
        }

        test("shiftRaw moves both waypoints by the delta and refuses a foreign shape") {
            val moved = StmJogSlide.shiftRaw(raw = vertical, delta = -33f) as EdgeRoute.OrthogonalRounded
            moved.waypoints shouldBe listOf(p(100f, 162f), p(113.26f, 162f))
            moved.source shouldBe vertical.source
            moved.target shouldBe vertical.target
            StmJogSlide.shiftRaw(raw = EdgeRoute.Direct(source = p(0f, 0f), target = p(0f, 9f)), delta = 1f) shouldBe null
            StmJogSlide.shiftRaw(raw = vertical, delta = Float.NaN) shouldBe null
            val d = StmJogSlide.delta(current = vertical, candidate = StmJogSlide.candidates(vertical)[0])!!
            (abs(d + 33f) < 0.001f) shouldBe true
        }
    })
