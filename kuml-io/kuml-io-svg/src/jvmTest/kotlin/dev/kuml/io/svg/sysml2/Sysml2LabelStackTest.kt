package dev.kuml.io.svg.sysml2

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 49 - labels of edges between the same node pair are stacked when they collide (font independent). */
class Sysml2LabelStackTest :
    FunSpec({
        val top = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 200f, height = 60f))
        val bottom = Rect(origin = Point(x = 0f, y = 200f), size = Size(width = 200f, height = 60f))
        val nodes = listOf(top, bottom)

        fun route(x: Float) = listOf(Point(x = x, y = 60f), Point(x = x, y = 200f))

        fun label(
            cx: Float,
            cy: Float,
            w: Float,
            h: Float = 16f,
        ) = Rect(origin = Point(x = cx - w / 2f, y = cy - h / 2f), size = Size(width = w, height = h))

        fun overlap(
            a: Rect,
            b: Rect,
        ) = Sysml2LabelPlacement.overlaps(a = a, b = b)

        test("derive and trace between one pair do not overlap, derive first, at least 4 px apart") {
            val placed =
                mapOf(
                    "trace:A::B" to label(cx = 100f, cy = 130f, w = 50f),
                    "derive:A::B" to label(cx = 104f, cy = 130f, w = 82f),
                )
            val routes = mapOf("trace:A::B" to route(x = 100f), "derive:A::B" to route(x = 104f))
            val out = Sysml2LabelPlacement.stackSamePairLabels(placed = placed, routes = routes, nodes = nodes)
            val d = out.getValue("derive:A::B")
            val t = out.getValue("trace:A::B")
            overlap(d, t) shouldBe false
            // vertical route: the labels stand side by side, derive left of trace, gap >= 4
            (d.origin.x + d.size.width + 4f <= t.origin.x + 0.001f) shouldBe true
            // second run gives the identical result
            Sysml2LabelPlacement.stackSamePairLabels(placed = placed, routes = routes, nodes = nodes) shouldBe out
        }

        test("a horizontal route stacks vertically") {
            val left = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 60f, height = 100f))
            val right = Rect(origin = Point(x = 300f, y = 0f), size = Size(width = 60f, height = 100f))
            val hr = { y: Float -> listOf(Point(x = 60f, y = y), Point(x = 300f, y = y)) }
            val placed =
                mapOf(
                    "derive:A::B" to label(cx = 180f, cy = 50f, w = 82f),
                    "trace:A::B" to label(cx = 180f, cy = 54f, w = 50f),
                )
            val out =
                Sysml2LabelPlacement.stackSamePairLabels(
                    placed = placed,
                    routes = mapOf("derive:A::B" to hr(50f), "trace:A::B" to hr(54f)),
                    nodes = listOf(left, right),
                )
            val d = out.getValue("derive:A::B")
            val t = out.getValue("trace:A::B")
            overlap(d, t) shouldBe false
            (t.origin.y >= d.origin.y + d.size.height + 4f - 0.001f) shouldBe true
        }

        test("a single edge per pair, or labels that do not collide, keep their rectangle") {
            val single = mapOf("derive:A::B" to label(cx = 100f, cy = 130f, w = 82f))
            Sysml2LabelPlacement.stackSamePairLabels(placed = single, routes = mapOf("derive:A::B" to route(100f)), nodes = nodes) shouldBe
                single
            val apart =
                mapOf(
                    "derive:A::B" to label(cx = 40f, cy = 130f, w = 50f),
                    "trace:A::B" to label(cx = 160f, cy = 130f, w = 50f),
                )
            Sysml2LabelPlacement.stackSamePairLabels(
                placed = apart,
                routes = mapOf("derive:A::B" to route(40f), "trace:A::B" to route(160f)),
                nodes = nodes,
            ) shouldBe apart
        }
    })
