@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** PAL Welle X3 (o2): corridor separation never drives a flow through a foreign node. */
class PalWelleX3ActCorridorTest :
    FunSpec({
        fun route(vararg p: Point) =
            EdgeRoute.OrthogonalRounded(source = p.first(), target = p.last(), waypoints = p.drop(1).dropLast(1), cornerRadiusPx = 0f)

        fun horizontalY(r: EdgeRoute): Float = (r as EdgeRoute.OrthogonalRounded).waypoints.first().y

        // o1 runs at y=421 below its source (ends at y=385); o2 would be pushed from y=429 to y=441, into the node at y=433..493.
        val o1 = route(Point(530f, 385f), Point(530f, 421f), Point(1068f, 421f), Point(1078f, 457f))
        val o2 = route(Point(822f, 385f), Point(822f, 429f), Point(1092f, 429f), Point(1083f, 457f))
        val node = Rect(origin = Point(x = 664f, y = 433f), size = Size(width = 217f, height = 60f))
        val routes = mapOf(EdgeId("o1") to o1, EdgeId("o2") to o2)

        test("without obstacles the later edge is pushed down by 12 px (unchanged behaviour)") {
            val out = ActCorridorSeparation.apply(routes)
            horizontalY(out.getValue(EdgeId("o2"))) shouldBe 441f
        }

        test("with a foreign node below the corridor the later edge goes above the earlier one and clears the node") {
            val out =
                ActCorridorSeparation.apply(
                    routes = routes,
                    obstacles = mapOf("sperren" to node),
                    endpoints = mapOf(EdgeId("o1") to setOf("a", "b"), EdgeId("o2") to setOf("c", "d")),
                )
            val y = horizontalY(out.getValue(EdgeId("o2")))
            (y < 433f - 5f || y > 493f + 5f) shouldBe true
            (abs(y - 421f) >= 12f - 0.01f) shouldBe true
        }

        test("an own endpoint node is not an obstacle") {
            val out =
                ActCorridorSeparation.apply(
                    routes = routes,
                    obstacles = mapOf("sperren" to node),
                    endpoints = mapOf(EdgeId("o2") to setOf("sperren", "x")),
                )
            horizontalY(out.getValue(EdgeId("o2"))) shouldBe 441f
        }
    })
