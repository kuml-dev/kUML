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

private fun pt(
    x: Float,
    y: Float,
) = Point(x = x, y = y)

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** PAL Welle R: the lateral slide, geometry only. */
class StmLateralSlideTest :
    FunSpec(body = {
        timeout = 60_000L

        // A straight vertical route from (100, 300) up to (100, 150).
        val straight =
            EdgeRoute.OrthogonalRounded(
                source = pt(100f, 300f),
                target = pt(100f, 150f),
                waypoints = emptyList(),
                cornerRadiusPx = 0f,
            )

        // Long / jog / long: 75 px segment at the source, 10 px jog at y = 225, 75 px segment at the target.
        fun ljl(
            jogAt: Float = 225f,
            jog: Float = 10f,
        ) = EdgeRoute.OrthogonalRounded(
            source = pt(100f, 300f),
            target = pt(100f + jog, 150f),
            waypoints = listOf(pt(100f, jogAt), pt(100f + jog, jogAt)),
            cornerRadiusPx = 0f,
        )

        fun wp(r: EdgeRoute) = (r as EdgeRoute.OrthogonalRounded).waypoints

        val nodes = mapOf("S" to box(0f, 300f, 300f, 40f), "T" to box(0f, 110f, 300f, 40f))
        val ends = mapOf("e" to ("S" to "T"))

        fun accepts(
            route: EdgeRoute,
            candidate: EdgeRoute,
            others: Map<String, EdgeRoute> = emptyMap(),
            otherEnds: Map<String, Pair<String, String>> = emptyMap(),
            frames: List<Rect> = emptyList(),
            nodeBoxes: Map<String, Rect> = nodes,
        ) = StmLateralSlide.acceptsGeometry(
            id = "e",
            candidate = candidate,
            routes = mapOf("e" to route) + others,
            ends = ends + otherEnds,
            nodes = nodeBoxes,
            frames = frames,
        )

        fun line(
            x: Float,
            from: Float,
            to: Float,
        ) = EdgeRoute.OrthogonalRounded(source = pt(x, from), target = pt(x, to), waypoints = emptyList(), cornerRadiusPx = 0f)

        test("a straight route gets eight whole-route candidates in the fixed order -8, +8, -16, +16 ...") {
            val c = StmLateralSlide.candidates(straight)
            c shouldHaveSize 8
            c.map { it.source.x } shouldBe listOf(92f, 108f, 84f, 116f, 76f, 124f, 68f, 132f)
            c.forEach {
                it.source.x shouldBe it.target.x
                it.source.y shouldBe 300f
                it.target.y shouldBe 150f
                wp(it).shouldBeEmpty()
            }
        }

        test("a horizontal straight route shifts on y") {
            val h = EdgeRoute.Direct(source = pt(300f, 100f), target = pt(150f, 100f))
            val c = StmLateralSlide.candidates(h)
            c shouldHaveSize 8
            c.map { it.source.y } shouldBe listOf(92f, 108f, 84f, 116f, 76f, 124f, 68f, 132f)
            c.forEach { it.source.x shouldBe 300f }
        }

        test("long / jog / long: the target segment first when both are equally long, the jog keeps its side and stays at most 32 px") {
            val c = StmLateralSlide.candidates(ljl())
            // Target segment: -8 (jog 2), +8 (18), +16 (26); -16 flips the jog, +24 would make it 34. Then the source segment.
            c.map { it.target.x } shouldBe listOf(102f, 118f, 126f, 110f, 110f, 110f)
            c.map { it.source.x } shouldBe listOf(100f, 100f, 100f, 92f, 108f, 84f)
            c.forEach { wp(it) shouldHaveSize 2 }
        }

        test("long / jog / long: the longer segment comes first") {
            val c = StmLateralSlide.candidates(ljl(jogAt = 200f))
            // source segment 100, target segment 50: the first candidates move the source segment.
            c.first().source.x shouldBe 92f
            c.first().target.x shouldBe 110f
        }

        test("a jog that shrinks to nothing turns the route straight") {
            val c = StmLateralSlide.candidates(ljl(jog = 8f))
            val collapsed = c.first { wp(it).isEmpty() }
            collapsed.source.x shouldBe collapsed.target.x
            c.none { wp(it).isNotEmpty() && Math.abs(wp(it)[0].x - wp(it)[1].x) < 0.5f } shouldBe true
        }

        test("every other shape has no candidate") {
            StmLateralSlide.candidates(EdgeRoute.Direct(source = pt(0f, 0f), target = pt(50f, 100f))).shouldBeEmpty()
            StmLateralSlide.candidates(EdgeRoute.Direct(source = pt(0f, 0f), target = pt(0f, 0f))).shouldBeEmpty()
            StmLateralSlide.candidates(ljl().copy(waypoints = listOf(pt(100f, 225f)))).shouldBeEmpty()
            StmLateralSlide.candidates(ljl().copy(waypoints = wp(ljl()) + pt(110f, 200f))).shouldBeEmpty()
            // A jog longer than the maximum.
            StmLateralSlide.candidates(ljl(jog = 40f)).shouldBeEmpty()
            StmLateralSlide
                .candidates(
                    EdgeRoute.Bezier(source = pt(0f, 0f), target = pt(0f, 100f), controlPoints = listOf(pt(30f, 50f))),
                ).shouldBeEmpty()
            // Not finite.
            StmLateralSlide.candidates(straight.copy(target = pt(Float.NaN, 150f))).shouldBeEmpty()
            StmLateralSlide.candidates(straight.copy(source = pt(100f, Float.POSITIVE_INFINITY))).shouldBeEmpty()
        }

        test("delta and shiftRaw round-trip every candidate; a foreign shape or NaN is refused") {
            for (route in listOf(straight, ljl(), ljl(jogAt = 200f))) {
                for (cand in StmLateralSlide.candidates(route)) {
                    val slide = StmLateralSlide.delta(current = route, candidate = cand)!!
                    StmLateralSlide.shiftRaw(raw = route, slide = slide) shouldBe cand
                }
            }
            val whole = StmLateralSlide.Slide(StmLateralSlide.Part.WHOLE, 8f)
            StmLateralSlide.shiftRaw(raw = ljl(), slide = whole) shouldBe null
            StmLateralSlide.shiftRaw(raw = straight, slide = StmLateralSlide.Slide(StmLateralSlide.Part.TARGET_SEGMENT, 8f)) shouldBe null
            StmLateralSlide.shiftRaw(raw = straight, slide = StmLateralSlide.Slide(StmLateralSlide.Part.WHOLE, Float.NaN)) shouldBe null
            StmLateralSlide.delta(current = straight, candidate = straight) shouldBe null
            // The flow coordinate of an end changed: not a lateral move.
            StmLateralSlide.delta(current = straight, candidate = straight.copy(source = pt(108f, 290f))) shouldBe null
        }

        test("a free corridor accepts every candidate") {
            StmLateralSlide.candidates(straight).forEach { accepts(straight, it) shouldBe true }
        }

        test("a candidate that crosses a foreign route more often is rejected") {
            // A horizontal foreign edge reaching from x = 104 to x = 140: crossed by +8 (x = 108), not by -8 (x = 92).
            val f = mapOf("f" to line(0f, 0f, 0f).copy(source = pt(104f, 200f), target = pt(140f, 200f)))
            val c = StmLateralSlide.candidates(straight)
            accepts(straight, c[0], f, mapOf("f" to ("A" to "B"))) shouldBe true
            accepts(straight, c[1], f, mapOf("f" to ("A" to "B"))) shouldBe false
        }

        test("a parallel foreign segment closer than 16 px to the moved segment rejects it") {
            // A vertical foreign edge at x = 118: 10 px from +8 (x = 108), 26 px from -8 (x = 92).
            val f = mapOf("f" to line(118f, 280f, 170f))
            val c = StmLateralSlide.candidates(straight)
            accepts(straight, c[1], f, mapOf("f" to ("A" to "B"))) shouldBe false
            accepts(straight, c[0], f, mapOf("f" to ("A" to "B"))) shouldBe true
        }

        test("a foreign segment that only touches the moved segment's span at its tip does not reject") {
            // Overlap below PARALLEL_MIN_OVERLAP_PX: the foreign edge ends 5 px into the moved segment.
            val f = mapOf("f" to line(104f, 150f, 155f))
            accepts(straight, StmLateralSlide.candidates(straight)[1], f, mapOf("f" to ("A" to "B"))) shouldBe true
        }

        test("a composite frame side closer than 12 px to the moved segment rejects it") {
            // The left side of the frame is at x = 84: 8 px from the candidate x = 92, 16 px from the route at x = 100.
            val frame = box(84f, 160f, 200f, 100f)
            val c = StmLateralSlide.candidates(straight)
            accepts(straight, c[0], frames = listOf(frame)) shouldBe false
            accepts(straight, c[1], frames = listOf(frame)) shouldBe true
        }

        test("a node closer than 12 px to the moved segment rejects it") {
            val near = nodes + ("N" to box(50f, 200f, 34f, 40f)) // right side at x = 84, 8 px from x = 92
            val c = StmLateralSlide.candidates(straight)
            accepts(straight, c[0], nodeBoxes = near) shouldBe false
            accepts(straight, c[1], nodeBoxes = near) shouldBe true
        }

        test("an end point closer than 12 px to a node corner rejects the candidate") {
            val narrow = mapOf("S" to box(60f, 300f, 60f, 40f), "T" to box(0f, 110f, 300f, 40f)) // S spans x 60..120, usable 72..108
            val c = StmLateralSlide.candidates(straight)
            accepts(straight, c[0], nodeBoxes = narrow) shouldBe true // 92
            accepts(straight, c[1], nodeBoxes = narrow) shouldBe true // 108
            accepts(straight, c[3], nodeBoxes = narrow) shouldBe false // 116 is inside the 12 px inset
        }

        test("a node that is missing for a moved end rejects the candidate") {
            accepts(straight, StmLateralSlide.candidates(straight)[0], nodeBoxes = mapOf("T" to nodes.getValue("T"))) shouldBe false
        }

        test("a moved end point that comes closer than 16 px to another end point on the same node is rejected") {
            // Another route ends on S at x = 112: 20 px from the old position, 4 px from x = 108, 12 px from... x = 92 is 20 px away.
            val other = mapOf("o" to line(112f, 220f, 300f).copy(source = pt(112f, 220f), target = pt(112f, 300f)))
            val oe = mapOf("o" to ("X" to "S"))
            val c = StmLateralSlide.candidates(straight)
            accepts(straight, c[1], other, oe) shouldBe false
            accepts(straight, c[0], other, oe) shouldBe true
        }

        test("an end point that is already close may move away from its neighbour") {
            val other = mapOf("o" to line(106f, 295f, 300f))
            val oe = mapOf("o" to ("X" to "S"))
            // 6 px apart before; -8 moves to 14 px apart (still < 16 but farther), +8 to 2 px (closer).
            val c = StmLateralSlide.candidates(straight)
            accepts(straight, c[0], other, oe) shouldBe true
            accepts(straight, c[1], other, oe) shouldBe false
        }

        test("an end point of another route on the old route that is not on the new one rejects the candidate") {
            val anchor = mapOf("a" to EdgeRoute.Direct(source = pt(100f, 220f), target = pt(160f, 220f)))
            accepts(straight, StmLateralSlide.candidates(straight)[0], anchor, mapOf("a" to ("N" to "N2"))) shouldBe false
        }

        test("a candidate shifted by more than the maximum, or not finite, is rejected") {
            accepts(straight, line(60f, 300f, 150f)) shouldBe false // 40 px
            accepts(straight, line(Float.NaN, 300f, 150f)) shouldBe false
        }

        test("a candidate for an unknown id is rejected") {
            StmLateralSlide.acceptsGeometry("zz", straight, mapOf("e" to straight), ends, nodes, emptyList()) shouldBe false
        }
    })
