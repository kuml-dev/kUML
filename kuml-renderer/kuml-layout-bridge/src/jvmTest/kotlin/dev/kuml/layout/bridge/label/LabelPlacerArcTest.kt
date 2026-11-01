@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private fun r(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** PAL wave 2D-a: arc-length candidates of the one label placer (state machines). */
class LabelPlacerArcTest :
    FunSpec(body = {
        val size = Size(width = 40f, height = 14f)
        val east = listOf(Point(x = 0f, y = 100f), Point(x = 200f, y = 100f))
        val west = east.reversed()

        fun req(
            id: String,
            poly: List<Point>,
            flags: List<Int> = emptyList(),
        ) = LabelRequest(id = id, role = LabelRole.EDGE_CENTER, ownerId = id, size = size, polyline = poly, segmentFlags = flags)

        fun place(
            requests: List<LabelRequest>,
            extra: (ObstacleIndex) -> Unit = {},
            costs: LabelCostModel = Sysml2DiagramLabelPlan.STM_COSTS,
        ): Map<String, PlacedLabel> {
            val index = ObstacleIndex()
            for (q in requests) index.addPolyline(kind = ObstacleKind.EDGE, pts = q.polyline, ownerId = q.ownerId)
            extra(index)
            return LabelPlacer.place(requests = requests, index = index, costs = costs)
        }

        fun distance(
            rect: Rect,
            poly: List<Point>,
        ) = LabelPlacer.rectDistanceToPolyline(r = rect, pts = poly)

        test("free edge: label at the middle, right of the direction of travel (below an eastbound line), near the line") {
            val p = place(listOf(req("a", east))).getValue("a")
            p.onLine shouldBe false
            p.clean shouldBe true
            val cx = p.rect.origin.x + p.rect.size.width / 2f
            abs(cx - 100f) shouldBeLessThanOrEqual 0.01f
            p.rect.origin.y shouldBeGreaterThan 100f
            distance(p.rect, east) shouldBeLessThanOrEqual 14f
            p.segment shouldBe 0
        }

        test("a westbound line puts the label above it (right of the travel direction)") {
            val p = place(listOf(req("a", west))).getValue("a")
            (p.rect.origin.y + p.rect.size.height) shouldBeLessThanOrEqual 100f
        }

        test("closely spaced antiparallel edges get labels on the outer, opposite sides") {
            val b = listOf(Point(x = 200f, y = 108f), Point(x = 0f, y = 108f))
            val p = place(listOf(req("a", east), req("b", b)))
            val ra = p.getValue("a").rect
            val rb = p.getValue("b").rect
            ((ra.origin.y + ra.size.height / 2f) < 100f) shouldBe true
            ((rb.origin.y + rb.size.height / 2f) > 108f) shouldBe true
            LabelPlacerTestSupport.overlaps(ra, rb) shouldBe false
        }

        test("obstacles at the middle on both sides move the label along the edge") {
            val p =
                place(
                    listOf(req("a", east)),
                    extra = {
                        it.addRect(kind = ObstacleKind.NODE, r = r(80f, 101f, 40f, 40f), ownerId = "n")
                        it.addRect(kind = ObstacleKind.NODE, r = r(80f, 59f, 40f, 40f), ownerId = "m")
                    },
                ).getValue("a")
            p.onLine shouldBe false
            val cx = p.rect.origin.x + p.rect.size.width / 2f
            (abs(cx - 100f) > 1f) shouldBe true
            distance(p.rect, east) shouldBeLessThanOrEqual 14f
        }

        test("avoid rectangles are hard obstacles of one request only") {
            val avoid = r(0f, 101f, 200f, 60f)
            val q = req("a", east).copy(avoid = listOf(avoid))
            val p = place(listOf(q)).getValue("a")
            (p.rect.origin.y + p.rect.size.height) shouldBeLessThanOrEqual 100f
        }

        test("shared and short segments are avoided when another segment is free") {
            val poly = listOf(Point(x = 0f, y = 100f), Point(x = 100f, y = 100f), Point(x = 100f, y = 300f))
            val flagged = req("a", poly, flags = listOf(SEGMENT_SHARED, 0))
            val p = place(listOf(flagged)).getValue("a")
            p.segment shouldBe 1
        }

        test("deterministic: two runs give the same result") {
            val reqs = listOf(req("a", east), req("b", listOf(Point(x = 100f, y = 0f), Point(x = 100f, y = 200f))))
            place(reqs) shouldBe place(reqs)
        }

        test("per request budget lets all of sixty labels reach a clean spot") {
            val reqs =
                (0 until 60).map { i ->
                    val y = 20f + i * 40f
                    req("e%02d".format(i), listOf(Point(x = 0f, y = y), Point(x = 200f, y = y)))
                }
            val p = place(reqs)
            p.size shouldBe 60
            p.values.all { it.clean && !it.onLine } shouldBe true
        }

        test("default cost model is unchanged: segment mode, no arc candidates") {
            val m = LabelCostModel()
            m.edgeCandidateMode shouldBe EdgeCandidateMode.SEGMENTS
            m.perRequestWorkBudget shouldBe null
            val p =
                LabelPlacer.place(
                    requests = listOf(req("a", east)),
                    index = ObstacleIndex().also { it.addPolyline(ObstacleKind.EDGE, east, "a") },
                )
            p.getValue("a").segment shouldBe -1
        }
    })

internal object LabelPlacerTestSupport {
    fun overlaps(
        a: Rect,
        b: Rect,
    ): Boolean =
        a.origin.x < b.origin.x + b.size.width &&
            b.origin.x < a.origin.x + a.size.width &&
            a.origin.y < b.origin.y + b.size.height &&
            b.origin.y < a.origin.y + a.size.height
}
