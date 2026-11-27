package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/**
 * PAL Welle S: a port label that still touches a foreign part, pin, title, label, line or the border of its own part after the
 * fixed ranking gets a bounded second search (at most 12 px from the pin); it moves only for strictly fewer collisions and
 * reports the rest. A clean label is untouched, bit for bit.
 */
class PortLabelRepairTest :
    FunSpec(body = {
        // North port of the part "box" (its top border runs through the pin centre, y = 105), label 40 x 14.
        val pin = box(x = 100f, y = 100f, w = 10f, h = 10f)
        val size = Size(width = 40f, height = 14f)
        val on = LabelCostModel(portRepair = true)
        val off = LabelCostModel(portRepair = false)

        fun index(vararg extra: Pair<String, Rect>): ObstacleIndex {
            val i = ObstacleIndex()
            i.addRect(kind = ObstacleKind.NODE, r = box(x = 50f, y = 105f, w = 150f, h = 150f), ownerId = "box")
            for ((owner, r) in extra) i.addRect(kind = ObstacleKind.NODE, r = r, ownerId = owner)
            return i
        }

        fun request(tangent: Int = 0) =
            LabelRequest(
                id = "p",
                role = LabelRole.PORT,
                ownerId = "box::p",
                size = size,
                anchorRect = pin,
                outward = LabelSide.NORTH,
                parentId = "box",
                preferredTangent = tangent,
            )

        fun place(
            costs: LabelCostModel,
            index: ObstacleIndex,
            tangent: Int = 0,
        ): PlacedLabel = LabelPlacer.place(requests = listOf(request(tangent)), index = index, costs = costs).getValue("p")

        fun gap(r: Rect) = LabelPlacer.rectGap(r = r, pin = pin)

        // The ranked first position of this label (right of the pin, outside) is x 114..154, y 89..103.
        test("a clean label is untouched: same rectangle, no collisions, no blockers") {
            val before = place(costs = off, index = index())
            val after = place(costs = on, index = index())
            after.rect shouldBe before.rect
            after.violations shouldBe 0
            after.blockers shouldBe emptyList()
            after.hintMissed shouldBe before.hintMissed
            after.clean shouldBe before.clean
        }

        test("a part 1 px beside the label: the label moves away within 12 px and has no collision left") {
            val neighbour = "fremd" to box(x = 155f, y = 60f, w = 40f, h = 43f)
            val before = place(costs = off, index = index(neighbour))
            LabelPlacer.portViolations(rect = before.rect, req = request(), index = index(neighbour)) shouldBe 1
            val after = place(costs = on, index = index(neighbour))
            (after.rect != before.rect) shouldBe true
            after.violations shouldBe 0
            (gap(after.rect) <= LabelPlacer.PORT_REPAIR_MAX_DISTANCE_PX + 0.01f) shouldBe true
        }

        test("touching counts: a part flush against the label (0 px) is a collision and the label moves") {
            val flush = "fremd" to box(x = 154f, y = 60f, w = 40f, h = 43f)
            val before = place(costs = off, index = index(flush))
            LabelPlacer.portViolations(rect = before.rect, req = request(), index = index(flush)) shouldBe 1
            val after = place(costs = on, index = index(flush))
            after.violations shouldBe 0
            (after.rect != before.rect) shouldBe true
        }

        test("the border of the own part: an outside label 1 px above the line counts, 2 px does not; inside mirrors it") {
            val req = request()
            val idx = index()
            val outer1 = box(x = 114f, y = 90f, w = 40f, h = 14f) // bottom 104 = 1 px above the border line y = 105
            val outer2 = box(x = 114f, y = 89f, w = 40f, h = 14f) // bottom 103 = 2 px
            LabelPlacer.portViolations(rect = outer1, req = req, index = idx) shouldBe 1
            LabelPlacer.portViolations(rect = outer2, req = req, index = idx) shouldBe 0
            val inner1 = box(x = 114f, y = 106f, w = 40f, h = 14f) // top 106 = 1 px below the line
            val inner2 = box(x = 114f, y = 107f, w = 40f, h = 14f)
            LabelPlacer.portViolations(rect = inner1, req = req, index = idx) shouldBe 1
            LabelPlacer.portViolations(rect = inner2, req = req, index = idx) shouldBe 0
            // across the border
            LabelPlacer.portViolations(rect = box(x = 114f, y = 98f, w = 40f, h = 14f), req = req, index = idx) shouldBe 1
        }

        test("the own pin and the own part do not count, a foreign pin, title, label and edge do") {
            val idx = index()
            idx.addRect(kind = ObstacleKind.PORT, r = pin) // the own pin square, no owner
            val r = box(x = 114f, y = 89f, w = 40f, h = 14f)
            LabelPlacer.portViolations(rect = r, req = request(), index = idx) shouldBe 0
            idx.addRect(kind = ObstacleKind.PORT, r = box(x = 150f, y = 88f, w = 10f, h = 10f))
            idx.addRect(kind = ObstacleKind.COMPOSITE_TITLE, r = box(x = 120f, y = 60f, w = 30f, h = 30f))
            idx.addRect(kind = ObstacleKind.PORT_LABEL, r = box(x = 120f, y = 80f, w = 30f, h = 12f), ownerId = "other::q")
            idx.addPolyline(kind = ObstacleKind.EDGE, pts = listOf(Point(x = 130f, y = 50f), Point(x = 130f, y = 99f)), ownerId = "e1")
            val why = ArrayList<String>()
            LabelPlacer.portViolations(rect = r, req = request(), index = idx, blockers = why) shouldBe 4
            why shouldContain "edge e1"
            why shouldContain "title"
        }

        test("a hint side that is blocked costs the hint: strictly fewer collisions win over labelAt") {
            val left = "fremd" to box(x = 20f, y = 60f, w = 77f, h = 43f) // 1 px from the label left of the pin (x 56..96)
            val free = place(costs = on, index = index(), tangent = -1)
            (free.rect.origin.x + free.rect.size.width <= pin.origin.x) shouldBe true
            free.hintMissed shouldBe false
            val blocked = place(costs = on, index = index(left), tangent = -1)
            blocked.violations shouldBe 0
            blocked.hintMissed shouldBe true
        }

        test("nothing is better: the ranked rectangle stays bit for bit and the collisions are reported with their blockers") {
            val sea = "sea" to box(x = 0f, y = 30f, w = 400f, h = 200f)
            val before = place(costs = off, index = index(sea))
            val after = place(costs = on, index = index(sea))
            after.rect shouldBe before.rect
            (after.violations > 0) shouldBe true
            after.blockers shouldContain "node sea"
            after.blockers.size shouldBe minOf(3, after.blockers.size)
        }

        test("the work budget decides: too little for the whole search keeps the ranked position") {
            val flush = "fremd" to box(x = 154f, y = 60f, w = 40f, h = 43f)
            val before = place(costs = off, index = index(flush))
            val starved = place(costs = LabelCostModel(portRepair = true, perRequestWorkBudget = 40L), index = index(flush))
            starved.rect shouldBe before.rect
            starved.violations shouldBe 1
        }

        test("the repair is off by default: the default cost model never moves a label") {
            val flush = "fremd" to box(x = 154f, y = 60f, w = 40f, h = 43f)
            val plain = place(costs = LabelCostModel(), index = index(flush))
            plain.violations shouldBe 0
            plain.blockers shouldBe emptyList()
            plain.rect shouldBe place(costs = off, index = index(flush)).rect
        }

        test("every other side repairs too: a part flush beside the ranked label never leaves more collisions") {
            for (side in listOf(LabelSide.EAST, LabelSide.SOUTH, LabelSide.WEST)) {
                val req = request().copy(outward = side)
                val ranked = LabelPlacer.place(requests = listOf(req), index = ObstacleIndex(), costs = off).getValue("p")
                val idx = ObstacleIndex()
                // a strip as high as the label, flush against its right edge
                idx.addRect(
                    kind = ObstacleKind.NODE,
                    r =
                        box(
                            x = ranked.rect.origin.x + ranked.rect.size.width,
                            y = ranked.rect.origin.y,
                            w = 30f,
                            h = ranked.rect.size.height,
                        ),
                    ownerId = "fremd",
                )
                val before = LabelPlacer.portViolations(rect = ranked.rect, req = req, index = idx)
                val after = LabelPlacer.place(requests = listOf(req), index = idx, costs = on).getValue("p")
                (after.violations <= before) shouldBe true
                (gap(after.rect) <= LabelPlacer.PART_CLEAR_MAX_DISTANCE_PX + 0.01f) shouldBe true
            }
        }
    })
