@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun inside(
    inner: Rect,
    outer: Rect,
) = inner.origin.x >= outer.origin.x &&
    inner.origin.y >= outer.origin.y &&
    inner.origin.x + inner.size.width <= outer.origin.x + outer.size.width &&
    inner.origin.y + inner.size.height <= outer.origin.y + outer.size.height

/** PAL wave 2D-a: obstacle model and request set of `Sysml2DiagramLabelPlan.planStmLabels`. */
class StmLabelPlanTest :
    FunSpec(body = {
        val size = Size(width = 60f, height = 18f)

        fun spec(id: String) = StmLabelSpec(id = id, lines = listOf("label"), size = size)

        fun line(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
        ) = EdgeRoute.Direct(source = Point(x = x0, y = y0), target = Point(x = x1, y = y1))

        test("the label of an outside edge never enters a composite it does not run through") {
            val comp = StmCompositeObstacle(id = "C", frame = rect(300f, 40f, 200f, 200f), headerBottom = 70f)
            val edges = mapOf("e" to line(0f, 100f, 296f, 100f))
            val plan =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = emptyMap(),
                    composites = listOf(comp),
                    labels = listOf(spec("e")),
                    frameTop = 20f,
                )
            val r = plan.labels.getValue("e").rect
            (r.origin.x + r.size.width <= 300f) shouldBe true
        }

        test("header band and divider of a composite are not covered") {
            val comp =
                StmCompositeObstacle(
                    id = "C",
                    frame = rect(100f, 40f, 300f, 200f),
                    headerBottom = 70f,
                    dividers = listOf(Point(250f, 70f) to Point(250f, 240f)),
                )
            val edges = mapOf("e" to line(110f, 150f, 390f, 150f))
            val plan =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = emptyMap(),
                    composites = listOf(comp),
                    labels = listOf(spec("e")),
                    frameTop = 20f,
                )
            val r = plan.labels.getValue("e").rect
            val coversDivider = r.origin.x < 250f && r.origin.x + r.size.width > 250f
            coversDivider shouldBe false
            (inside(r, rect(100f, 70f, 300f, 170f))) shouldBe true
        }

        test("nothing is placed above frameTop") {
            val edges = mapOf("e" to line(0f, 22f, 200f, 22f))
            val plan =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = emptyMap(),
                    composites = emptyList(),
                    labels = listOf(spec("e")),
                    frameTop = 20f,
                )
            (
                plan.labels
                    .getValue("e")
                    .rect.origin.y >= 20f
            ) shouldBe true
        }

        test("sixty edges all get a label") {
            val edges = (0 until 60).associate { i -> "e%02d".format(i) to line(0f, 20f + 40f * i, 300f, 20f + 40f * i) }
            val plan =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = emptyMap(),
                    composites = emptyList(),
                    labels = edges.keys.map { spec(it) },
                    frameTop = 0f,
                )
            plan.labels.size shouldBe 60
            plan.labels.values.all { it.clean } shouldBe true
        }

        test("a bezier route is sampled, not reduced to the straight line") {
            val bez =
                EdgeRoute.Bezier(
                    source = Point(0f, 0f),
                    target = Point(100f, 0f),
                    controlPoints = listOf(Point(30f, 80f), Point(70f, 80f)),
                )
            val pts = Sysml2DiagramLabelPlan.stmPolylineOf(bez)
            (pts.size > 4) shouldBe true
            (pts.maxOf { it.y } > 30f) shouldBe true
        }

        test("an empty label list gives an empty plan") {
            Sysml2DiagramLabelPlan
                .planStmLabels(
                    edges = emptyMap(),
                    nodes = emptyMap(),
                    composites = emptyList(),
                    labels = emptyList(),
                    frameTop = 0f,
                ).labels.size shouldBe
                0
        }

        test("a state machine with 2500 labelled transitions is placed within a bounded time") {
            val n = 2500
            val edges = HashMap<String, EdgeRoute>()
            val specs = ArrayList<StmLabelSpec>()
            for (k in 0 until n) {
                val y = 20f + (k % 50) * 30f
                val x = (k / 50) * 90f
                edges["e$k"] = line(x, y, x + 80f, y + 12f)
                specs += spec("e$k")
            }
            val start = System.nanoTime()
            val plan =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = emptyMap(),
                    composites = emptyList(),
                    labels = specs,
                    frameTop = 0f,
                )
            val ms = (System.nanoTime() - start) / 1_000_000
            plan.labels.size shouldBe n
            (ms < 20_000) shouldBe true
        }
    })
