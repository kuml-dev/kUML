package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.edge.EdgeLabelKind
import dev.kuml.sysml2.edge.EdgeLabelPart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class StmLabelPlacerTest :
    FunSpec({
        fun vertical(x: Float = 100f) = EdgeRoute.Direct(source = Point(x = x, y = 0f), target = Point(x = x, y = 200f))

        fun p(
            t: String,
            k: EdgeLabelKind = EdgeLabelKind.Trigger,
        ) = EdgeLabelPart(text = t, kind = k)

        test("short parts stay on one line, long ones wrap one part per line and truncate with an ellipsis") {
            StmLabelPlacer.wrap(listOf(p("go"), p("[x > 1]", EdgeLabelKind.Guard))).first.size shouldBe 1
            val (lines, cut) =
                StmLabelPlacer.wrap(
                    listOf(
                        p("trigger"),
                        p("[" + "g".repeat(60) + "]", EdgeLabelKind.Guard),
                        p("/ " + "e".repeat(10), EdgeLabelKind.Effect),
                    ),
                )
            lines.size shouldBe 3
            cut shouldBe true
            lines[1].single().text.endsWith("…") shouldBe true
        }

        test("the first candidate (50 percent, right of the line) wins without collisions and keeps clear of the line") {
            val out = StmLabelPlacer.place(edges = listOf(Triple("a", vertical(), listOf(p("go")))), obstacles = emptyList())
            val b = out.getValue("a")
            b.cy shouldBe 100f
            (b.cx - b.w / 2f > 100f) shouldBe true
        }

        test("a blocked right side moves the label to the left; placement is deterministic") {
            val obstacle = Rect(origin = Point(x = 110f, y = 80f), size = Size(width = 80f, height = 40f))
            val e = listOf(Triple("a", vertical(), listOf(p("go"))))
            val one = StmLabelPlacer.place(edges = e, obstacles = listOf(obstacle))
            (one.getValue("a").cx < 100f) shouldBe true
            StmLabelPlacer.place(edges = e, obstacles = listOf(obstacle)) shouldBe one
        }

        test("two edges on the same path do not overlap each other") {
            val e = listOf(Triple("a", vertical(), listOf(p("aaaa"))), Triple("b", vertical(), listOf(p("bbbb"))))
            val out = StmLabelPlacer.place(edges = e, obstacles = emptyList())
            val a = out.getValue("a")
            val b = out.getValue("b")
            val overlap = abs(a.cx - b.cx) < (a.w + b.w) / 2f && abs(a.cy - b.cy) < (a.h + b.h) / 2f
            overlap shouldBe false
        }

        test("junction ends are spread at least 30 degrees apart on the circle outline") {
            val c = Rect(origin = Point(x = 90f, y = 90f), size = Size(width = 12f, height = 12f))
            val centre = Point(x = 96f, y = 96f)
            val routes =
                mapOf(
                    "e1" to EdgeRoute.Direct(source = Point(x = 96f, y = 0f), target = Point(x = 96f, y = 90f)),
                    "e2" to EdgeRoute.Direct(source = Point(x = 97f, y = 0f), target = Point(x = 97f, y = 90f)),
                    "e3" to EdgeRoute.Direct(source = Point(x = 95f, y = 0f), target = Point(x = 95f, y = 90f)),
                )
            val out =
                StmPseudostateEndpoints.spread(
                    routes = routes,
                    ends = listOf(Triple("e1", "s", "J"), Triple("e2", "s", "J"), Triple("e3", "s", "J")),
                    circles = mapOf("J" to c),
                    diamonds = emptyMap(),
                )
            val angles =
                out.values
                    .map { atan2(it.target.y - centre.y, it.target.x - centre.x) }
                    .sorted()
            for (i in 1 until angles.size) ((angles[i] - angles[i - 1]) >= Math.toRadians(29.0)) shouldBe true
            for (r in out.values) (abs(hypot(r.target.x - centre.x, r.target.y - centre.y) - 7f) < 0.01f) shouldBe true
        }
    })
