@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun gap(
    a: Rect,
    b: Rect,
): Float {
    val dx = maxOf(0f, maxOf(b.origin.x - (a.origin.x + a.size.width), a.origin.x - (b.origin.x + b.size.width)))
    val dy = maxOf(0f, maxOf(b.origin.y - (a.origin.y + a.size.height), a.origin.y - (b.origin.y + b.size.height)))
    return kotlin.math.hypot(dx, dy)
}

/** PAL Welle J2: the repair pass of the state-machine transition labels. */
class StmLabelRepairTest :
    FunSpec(body = {
        val size = Size(width = 60f, height = 18f)

        fun line(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
        ) = EdgeRoute.Direct(source = Point(x = x0, y = y0), target = Point(x = x1, y = y1))

        fun request(
            id: String,
            pts: List<Point>,
        ) = LabelRequest(id = id, role = LabelRole.EDGE_CENTER, ownerId = id, size = size, polyline = pts)

        fun horizontal(
            id: String,
            y: Float,
        ) = request(id = id, pts = listOf(Point(0f, y), Point(200f, y)))

        /** The routes of the requests as edge obstacles (owner = request id). */
        fun baseOf(vararg requests: LabelRequest): ObstacleIndex {
            val index = ObstacleIndex()
            for (r in requests) index.addPolyline(kind = ObstacleKind.EDGE, pts = r.polyline, ownerId = r.ownerId)
            return index
        }

        val costs = Sysml2DiagramLabelPlan.STM_COSTS

        test("candidates start at the middle of the line, right of the direction of travel before the left, nearest offset first") {
            val c = StmLabelRepair.repairCandidates(horizontal(id = "e", y = 100f))
            val first = c[0].rect
            val second = c[1].rect
            // Travelling right (x+), the right-hand side is below (y grows downwards): 3 px offset plus half the height.
            (first.origin.x + first.size.width / 2f) shouldBe 100f
            (first.origin.y + first.size.height / 2f) shouldBe (100f + 3f + 9f)
            (second.origin.y + second.size.height / 2f) shouldBe (100f - 3f - 9f)
            (c.size <= StmLabelRepair.MAX_CANDIDATES) shouldBe true
            // Further positions follow along the line, nearest to the middle first.
            val third = c[8].rect
            (kotlin.math.abs(third.origin.x + third.size.width / 2f - 100f) > 0f) shouldBe true
        }

        test("a candidate on a node, another label or a foreign line is never chosen") {
            val e = horizontal(id = "e", y = 100f)
            val index = baseOf(e)
            // A foreign line at y = 112 and a node below the line: the label must not touch either.
            index.addPolyline(kind = ObstacleKind.EDGE, pts = listOf(Point(0f, 112f), Point(200f, 112f)), ownerId = "f")
            index.addRect(kind = ObstacleKind.NODE, r = rect(60f, 102f, 80f, 6f), ownerId = "n")
            val other = Rect(origin = Point(60f, 70f), size = size)
            val placed0 = mapOf("e" to PlacedLabel(rect = rect(70f, 100f, 60f, 18f), onLine = true, clean = false))
            val r = StmLabelRepair.repair(requests = listOf(e), placed0 = placed0, base = index, costs = costs)
            r.unresolved shouldBe emptyList()
            val chosen = r.placed.getValue("e").rect
            r.placed.getValue("e").clean shouldBe true
            (gap(chosen, rect(60f, 102f, 80f, 6f)) >= 2f) shouldBe true
            // Not on the foreign line y = 112 (4 px clearance): the rectangle spans no y in [108, 116].
            (chosen.origin.y + chosen.size.height < 108f || chosen.origin.y > 116f) shouldBe true
            (gap(chosen, other) >= 0f) shouldBe true
        }

        test("two overlapping labels are separated; the one that was clean keeps its position") {
            val a = horizontal(id = "a", y = 100f)
            val b = horizontal(id = "b", y = 130f)
            val atA = rect(70f, 103f, 60f, 18f)
            // b was placed on top of a (no free position in the greedy run).
            val placed0 =
                mapOf(
                    "a" to PlacedLabel(rect = atA, onLine = false, clean = true),
                    "b" to PlacedLabel(rect = rect(75f, 110f, 60f, 18f), onLine = false, clean = false),
                )
            val r = StmLabelRepair.repair(requests = listOf(a, b), placed0 = placed0, base = baseOf(a, b), costs = costs)
            r.unresolved shouldBe emptyList()
            r.placed.getValue("a").rect shouldBe atA
            (gap(r.placed.getValue("a").rect, r.placed.getValue("b").rect) >= 2f) shouldBe true
            r.placed.getValue("b").clean shouldBe true
        }

        test("a label that was clean and collides with nothing is not touched") {
            val a = horizontal(id = "a", y = 100f)
            val far = horizontal(id = "far", y = 300f)
            val b = horizontal(id = "b", y = 130f)
            val atFar = rect(70f, 303f, 60f, 18f)
            val placed0 =
                mapOf(
                    "a" to PlacedLabel(rect = rect(70f, 103f, 60f, 18f), onLine = false, clean = true),
                    "b" to PlacedLabel(rect = rect(75f, 110f, 60f, 18f), onLine = false, clean = false),
                    "far" to PlacedLabel(rect = atFar, onLine = false, clean = true),
                )
            val r = StmLabelRepair.repair(requests = listOf(a, b, far), placed0 = placed0, base = baseOf(a, b, far), costs = costs)
            r.placed.getValue("far") shouldBe placed0.getValue("far")
        }

        test("a label without any free position stays as it was, is not clean and is reported") {
            val nodes = mapOf("above" to rect(-50f, 60f, 400f, 38f), "below" to rect(-50f, 102f, 400f, 38f))
            val edges = mapOf("e" to line(0f, 100f, 200f, 100f))
            val spec = StmLabelSpec(id = "e", lines = listOf("label"), size = size)
            val plan =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = nodes,
                    composites = emptyList(),
                    labels = listOf(spec),
                    frameTop = 0f,
                )
            plan.unresolved shouldBe listOf("e")
            plan.labels.getValue("e").clean shouldBe false
            // The same scene with the repair switched off reports nothing (the other diagram types and the old behaviour).
            val off =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = nodes,
                    composites = emptyList(),
                    labels = listOf(spec),
                    frameTop = 0f,
                    costs = costs.copy(hardExclusions = false),
                )
            off.unresolved shouldBe emptyList()
        }

        test("the result is deterministic") {
            val nodes = mapOf("n" to rect(60f, 102f, 80f, 6f))
            val edges = mapOf("a" to line(0f, 100f, 200f, 100f), "b" to line(0f, 130f, 200f, 130f))
            val specs = listOf("a", "b").map { StmLabelSpec(id = it, lines = listOf("label"), size = size) }

            fun run() =
                Sysml2DiagramLabelPlan.planStmLabels(edges = edges, nodes = nodes, composites = emptyList(), labels = specs, frameTop = 0f)
            run() shouldBe run()
        }

        test("an exhausted work budget ends the repair without a crash and keeps every label") {
            val a = horizontal(id = "a", y = 100f)
            val b = horizontal(id = "b", y = 130f)
            val placed0 =
                mapOf(
                    "a" to PlacedLabel(rect = rect(70f, 103f, 60f, 18f), onLine = false, clean = true),
                    "b" to PlacedLabel(rect = rect(75f, 110f, 60f, 18f), onLine = false, clean = false),
                )
            val r =
                StmLabelRepair.repair(
                    requests = listOf(a, b),
                    placed0 = placed0,
                    base = baseOf(a, b),
                    costs = costs.copy(totalWorkBudget = 1L),
                )
            r.placed.keys shouldBe setOf("a", "b")
        }

        test("a clean plan is returned unchanged") {
            val edges = mapOf("a" to line(0f, 100f, 200f, 100f), "b" to line(0f, 300f, 200f, 300f))
            val specs = listOf("a", "b").map { StmLabelSpec(id = it, lines = listOf("label"), size = size) }
            val on =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = emptyMap(),
                    composites = emptyList(),
                    labels = specs,
                    frameTop = 0f,
                )
            val off =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = emptyMap(),
                    composites = emptyList(),
                    labels = specs,
                    frameTop = 0f,
                    costs = costs.copy(hardExclusions = false),
                )
            on.labels shouldBe off.labels
            on.unresolved shouldBe emptyList()
        }
    })
