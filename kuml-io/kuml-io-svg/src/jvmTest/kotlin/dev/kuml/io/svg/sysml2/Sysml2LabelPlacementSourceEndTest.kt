@file:Suppress("RequireNamedArguments") // geometry fixtures with rectangle literals

package dev.kuml.io.svg.sysml2

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 59: pure-geometry checks of `placeAll(preferSourceEnd = ...)`. */
class Sysml2LabelPlacementSourceEndTest :
    StringSpec({
        val line = listOf(Point(0f, 100f), Point(400f, 100f))
        val req = LabelRequest(id = "e", polyline = line, w = 60f, h = 18f)
        val routes = mapOf("e" to line)

        fun cx(r: Rect) = r.origin.x + r.size.width / 2f

        "default keeps the label on the longest segment's middle" {
            val r = Sysml2LabelPlacement.placeAll(requests = listOf(req), allRoutes = routes, obstacles = emptyList()).getValue("e")
            cx(r) shouldBe 200f
        }

        "an explicit preferSourceEnd = false is identical to the default (UC, ACT, PAR, package stay untouched)" {
            val box = Rect(origin = Point(150f, 60f), size = Size(90f, 80f))
            val a = Sysml2LabelPlacement.placeAll(requests = listOf(req), allRoutes = routes, obstacles = listOf(box))
            val b =
                Sysml2LabelPlacement.placeAll(
                    requests = listOf(req),
                    allRoutes = routes,
                    obstacles = listOf(box),
                    preferSourceEnd = false,
                )
            a shouldBe b
        }

        "preferSourceEnd puts the label right after the source end" {
            val r =
                Sysml2LabelPlacement
                    .placeAll(requests = listOf(req), allRoutes = routes, obstacles = emptyList(), preferSourceEnd = true)
                    .getValue("e")
            cx(r) shouldBe 42f // 12 + along / 2
        }

        "a box at the source end pushes the label behind it" {
            val box = Rect(origin = Point(0f, 60f), size = Size(90f, 80f))
            val r =
                Sysml2LabelPlacement
                    .placeAll(requests = listOf(req), allRoutes = routes, obstacles = listOf(box), preferSourceEnd = true)
                    .getValue("e")
            (r.origin.x >= 90f) shouldBe true
        }

        "a label is never dropped when every candidate is blocked" {
            val wall = Rect(origin = Point(-10f, 0f), size = Size(500f, 200f))
            val r =
                Sysml2LabelPlacement
                    .placeAll(
                        requests = listOf(req),
                        allRoutes = routes,
                        obstacles = listOf(wall),
                        hardObstacles = listOf(wall),
                        preferSourceEnd = true,
                    )
            r.containsKey("e") shouldBe true
        }
    })
