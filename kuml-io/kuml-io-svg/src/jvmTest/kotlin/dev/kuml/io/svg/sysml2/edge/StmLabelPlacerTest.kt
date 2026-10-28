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

        val frame = Rect(origin = Point(x = 8f, y = 20f), size = Size(width = 100000f, height = 100000f))

        fun simple(
            id: String,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = StmObstacleNode(
            id = id,
            kind = StmObstacleKind.Simple,
            rect = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h)),
        )

        fun box(b: StmLabelBlock) = floatArrayOf(b.cx - b.w / 2f, b.cy - b.h / 2f, b.cx + b.w / 2f, b.cy + b.h / 2f)

        fun hitsSeg(
            b: StmLabelBlock,
            a: Point,
            c: Point,
        ): Boolean {
            val r = box(b)
            for (i in 0..100) {
                val x = a.x + (c.x - a.x) * i / 100f
                val y = a.y + (c.y - a.y) * i / 100f
                if (x > r[0] && x < r[2] && y > r[1] && y < r[3]) return true
            }
            return false
        }

        test("the first candidate (50 percent, right of the line) wins without collisions and keeps clear of the line") {
            val out = StmLabelPlacer.place(edges = listOf(Triple("a", vertical(), listOf(p("go")))), nodes = emptyList(), frame = frame)
            val b = out.getValue("a")
            b.cy shouldBe 100f
            (b.cx - b.w / 2f > 100f) shouldBe true
            b.anchor shouldBe StmLabelAnchor.Left
        }

        test("a blocked right side moves the label to the left; placement is deterministic") {
            val obstacle = simple("n", 110f, 80f, 80f, 40f)
            val e = listOf(Triple("a", vertical(), listOf(p("go"))))
            val one = StmLabelPlacer.place(edges = e, nodes = listOf(obstacle), frame = frame)
            (one.getValue("a").cx < 100f) shouldBe true
            one.getValue("a").anchor shouldBe StmLabelAnchor.Right
            StmLabelPlacer.place(edges = e, nodes = listOf(obstacle), frame = frame) shouldBe one
        }

        test("horizontal anchor segments centre the label above the line") {
            val h = EdgeRoute.Direct(source = Point(x = 0f, y = 100f), target = Point(x = 200f, y = 100f))
            val b = StmLabelPlacer.place(edges = listOf(Triple("a", h, listOf(p("go")))), nodes = emptyList(), frame = frame).getValue("a")
            b.anchor shouldBe StmLabelAnchor.Center
            (b.cy < 100f) shouldBe true
        }

        test("two edges on the same path do not overlap each other") {
            val e = listOf(Triple("a", vertical(), listOf(p("aaaa"))), Triple("b", vertical(), listOf(p("bbbb"))))
            val out = StmLabelPlacer.place(edges = e, nodes = emptyList(), frame = frame)
            val a = out.getValue("a")
            val b = out.getValue("b")
            val overlap = abs(a.cx - b.cx) < (a.w + b.w) / 2f && abs(a.cy - b.cy) < (a.h + b.h) / 2f
            overlap shouldBe false
        }

        test("a label never lies on a crossing edge") {
            val cross = EdgeRoute.Direct(source = Point(x = 40f, y = 100f), target = Point(x = 300f, y = 100f))
            val out =
                StmLabelPlacer.place(
                    edges = listOf(Triple("a", vertical(), listOf(p("go"))), Triple("b", cross, emptyList())),
                    nodes = emptyList(),
                    frame = frame,
                )
            hitsSeg(out.getValue("a"), cross.source, cross.target) shouldBe false
            hitsSeg(out.getValue("a"), Point(x = 100f, y = 0f), Point(x = 100f, y = 200f)) shouldBe false
        }

        test("an inner edge of a composite keeps its label inside, off the frame band and header") {
            val comp =
                StmObstacleNode(
                    id = "C",
                    kind = StmObstacleKind.Composite,
                    rect = Rect(origin = Point(x = 50f, y = 50f), size = Size(width = 300f, height = 300f)),
                    headerBottom = 78f,
                )
            val r = EdgeRoute.Direct(source = Point(x = 200f, y = 100f), target = Point(x = 200f, y = 300f))
            val b = StmLabelPlacer.place(edges = listOf(Triple("a", r, listOf(p("go")))), nodes = listOf(comp), frame = frame).getValue("a")
            val bx = box(b)
            (bx[0] > 54f && bx[2] < 346f && bx[1] > 82f && bx[3] < 346f) shouldBe true
        }

        test("an outer edge never puts its label inside a composite it does not enter") {
            val comp =
                StmObstacleNode(
                    id = "C",
                    kind = StmObstacleKind.Composite,
                    rect = Rect(origin = Point(x = 110f, y = 50f), size = Size(width = 200f, height = 100f)),
                    headerBottom = 78f,
                )
            val b =
                StmLabelPlacer
                    .place(
                        edges = listOf(Triple("a", vertical(), listOf(p("go")))),
                        nodes = listOf(comp),
                        frame = frame,
                    ).getValue("a")
            (b.cx < 100f) shouldBe true
        }

        test("a shared trunk is not used as anchor segment") {
            val shared = EdgeRoute.Direct(source = Point(x = 100f, y = 0f), target = Point(x = 100f, y = 200f))
            val own =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 100f, y = 0f),
                    target = Point(x = 300f, y = 220f),
                    waypoints = listOf(Point(x = 100f, y = 20f), Point(x = 300f, y = 20f)),
                    cornerRadiusPx = 0f,
                )
            val out =
                StmLabelPlacer.place(
                    edges = listOf(Triple("a", shared, emptyList()), Triple("b", own, listOf(p("go")))),
                    nodes = emptyList(),
                    frame = frame,
                )
            val b = out.getValue("b")
            // Anchored on the long horizontal / right-hand vertical part, not beside x = 100.
            (b.cx > 150f) shouldBe true
        }

        test("labels respect the top and left frame limit") {
            val r = EdgeRoute.Direct(source = Point(x = 10f, y = 22f), target = Point(x = 10f, y = 200f))
            val b = StmLabelPlacer.place(edges = listOf(Triple("a", r, listOf(p("go")))), nodes = emptyList(), frame = frame).getValue("a")
            (box(b)[0] >= 8f && box(b)[1] >= 20f) shouldBe true
        }

        test("bezier edges are followed along the curve, not the chord") {
            val curve =
                EdgeRoute.Bezier(
                    source = Point(x = 0f, y = 0f),
                    target = Point(x = 0f, y = 200f),
                    controlPoints = listOf(Point(x = 200f, y = 0f), Point(x = 200f, y = 200f)),
                )
            val b =
                StmLabelPlacer
                    .place(
                        edges = listOf(Triple("a", curve, listOf(p("go")))),
                        nodes = emptyList(),
                        frame = frame,
                    ).getValue("a")
            // The curve bulges to x of about 150 and runs through the chord-side candidates of a chord-based placer.
            val r = box(b)
            val onCurve =
                (0..200).any { i ->
                    val t = i / 200f
                    val u = 1f - t
                    val x = 3f * u * u * t * 200f + 3f * u * t * t * 200f
                    val y = 3f * u * t * t * 200f * 1f + t * t * t * 200f + 3f * u * u * t * 0f
                    x > r[0] && x < r[2] && y > r[1] && y < r[3]
                }
            onCurve shouldBe false
        }

        test("degenerate routes do not crash or loop") {
            val nan = EdgeRoute.Direct(source = Point(x = Float.NaN, y = 0f), target = Point(x = 5f, y = 5f))
            val dot = EdgeRoute.Direct(source = Point(x = 5f, y = 5f), target = Point(x = 5f, y = 5f))
            val out =
                StmLabelPlacer.place(
                    edges = listOf(Triple("a", nan, listOf(p("x"))), Triple("b", dot, listOf(p("y")))),
                    nodes = emptyList(),
                    frame = frame,
                )
            out.size shouldBe 2
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
