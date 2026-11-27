package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/**
 * PAL Welle V: a port label that is clean in the 2 px sense but stands closer than 6 px to a foreign part gets a second
 * placement pass (same candidates and 12 px reach as the repair of Welle S). It moves only for strictly fewer part collisions
 * and never for more 2 px collisions; a label that already keeps 6 px stays bit for bit.
 */
class PortLabelPartClearanceTest :
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

        fun request() =
            LabelRequest(
                id = "p",
                role = LabelRole.PORT,
                ownerId = "box::p",
                size = size,
                anchorRect = pin,
                outward = LabelSide.NORTH,
                parentId = "box",
            )

        fun place(
            costs: LabelCostModel,
            index: ObstacleIndex,
        ): PlacedLabel = LabelPlacer.place(requests = listOf(request()), index = index, costs = costs).getValue("p")

        fun partCollisions(
            r: Rect,
            index: ObstacleIndex,
        ) = LabelPlacer.partClearanceViolations(rect = r, req = request(), index = index)

        fun collisions(
            r: Rect,
            index: ObstacleIndex,
        ) = LabelPlacer.portViolations(rect = r, req = request(), index = index)

        // The ranked first position of this label (right of the pin, outside) is x 114..154, y 89..103.
        test("the clearance constant is 6 px") {
            LabelPlacer.PORT_PART_CLEAR_PX shouldBe 6f
        }

        test("4 px beside a foreign part: the label moves, keeps 6 px and no 2 px collision, within 12 px of the pin") {
            val neighbour = "fremd" to box(x = 158f, y = 60f, w = 40f, h = 43f)
            val before = place(costs = off, index = index(neighbour))
            collisions(r = before.rect, index = index(neighbour)) shouldBe 0
            partCollisions(r = before.rect, index = index(neighbour)) shouldBe 1
            val after = place(costs = on, index = index(neighbour))
            (after.rect != before.rect) shouldBe true
            partCollisions(r = after.rect, index = index(neighbour)) shouldBe 0
            collisions(r = after.rect, index = index(neighbour)) shouldBe 0
            after.violations shouldBe 0
            (LabelPlacer.rectGap(r = after.rect, pin = pin) <= LabelPlacer.PART_CLEAR_MAX_DISTANCE_PX + 0.01f) shouldBe true
        }

        test("6.5 px and exactly 6.0 px beside a foreign part: the label stays bit for bit") {
            for (x in listOf(160.5f, 160f)) {
                val neighbour = "fremd" to box(x = x, y = 60f, w = 40f, h = 43f)
                val before = place(costs = off, index = index(neighbour))
                partCollisions(r = before.rect, index = index(neighbour)) shouldBe 0
                val after = place(costs = on, index = index(neighbour))
                after.rect shouldBe before.rect
                after.violations shouldBe 0
            }
        }

        test("5.9 px counts as a collision, 6.0 px does not") {
            val near = index("fremd" to box(x = 159.9f, y = 60f, w = 40f, h = 43f))
            val ok = index("fremd" to box(x = 160f, y = 60f, w = 40f, h = 43f))
            val label = box(x = 114f, y = 89f, w = 40f, h = 14f)
            partCollisions(r = label, index = near) shouldBe 1
            partCollisions(r = label, index = ok) shouldBe 0
        }

        test("the own part and its ancestors are not foreign") {
            val i = ObstacleIndex()
            i.addRect(kind = ObstacleKind.NODE, r = box(x = 50f, y = 105f, w = 150f, h = 150f), ownerId = "outer::box")
            i.addRect(kind = ObstacleKind.NODE, r = box(x = 0f, y = 0f, w = 400f, h = 400f), ownerId = "outer")
            val req = request().copy(ownerId = "outer::box::p", parentId = "outer::box")
            LabelPlacer.partClearanceViolations(rect = box(x = 114f, y = 89f, w = 40f, h = 14f), req = req, index = i) shouldBe 0
        }

        test("nothing is better: parts closer than 6 px on every side keep the ranked position") {
            val ring =
                arrayOf(
                    "r" to box(x = 158f, y = 20f, w = 60f, h = 83f),
                    "l" to box(x = 0f, y = 20f, w = 92f, h = 83f),
                    "t" to box(x = 0f, y = 0f, w = 218f, h = 80f),
                )

            fun ringIndex(): ObstacleIndex {
                val i = index(*ring)
                // the inside of the own part is taken, too
                i.addRect(kind = ObstacleKind.PORT_LABEL, r = box(x = 50f, y = 106f, w = 150f, h = 150f), ownerId = "other")
                return i
            }
            val before = place(costs = off, index = ringIndex())
            val after = place(costs = on, index = ringIndex())
            partCollisions(r = before.rect, index = ringIndex()) shouldBe 1
            after.rect shouldBe before.rect
            collisions(r = after.rect, index = ringIndex()) shouldBe collisions(r = before.rect, index = ringIndex())
        }

        test("never worse: over a family of neighbours the 2 px collisions do not grow and the part collisions do not grow") {
            for (gapPx in listOf(0f, 1f, 2f, 3f, 4f, 5f)) {
                for (lineY in listOf(70f, 80f, 86f, 90f)) {
                    fun build(): ObstacleIndex {
                        val i = index("fremd" to box(x = 154f + gapPx, y = 60f, w = 40f, h = 43f))
                        i.addPolyline(
                            kind = ObstacleKind.EDGE,
                            pts = listOf(Point(x = 0f, y = lineY), Point(x = 300f, y = lineY)),
                            ownerId = "e",
                        )
                        return i
                    }
                    val before = place(costs = off, index = build())
                    val after = place(costs = on, index = build())
                    val v0 = collisions(r = before.rect, index = build())
                    val v1 = collisions(r = after.rect, index = build())
                    val p0 = partCollisions(r = before.rect, index = build())
                    val p1 = partCollisions(r = after.rect, index = build())
                    (p1 <= p0) shouldBe true
                    if (p0 == 0) {
                        // stage 1 may still move it for 2 px collisions, the part count must stay 0
                        p1 shouldBe 0
                    }
                    if (v0 == 0 && p0 > 0 && after.rect != before.rect) (v1 <= v0) shouldBe true
                }
            }
        }

        test("the repair is off by default: the second pass never moves a label") {
            val neighbour = "fremd" to box(x = 158f, y = 60f, w = 40f, h = 43f)
            val ranked = place(costs = off, index = index(neighbour))
            val dflt = LabelPlacer.place(requests = listOf(request()), index = index(neighbour)).getValue("p")
            dflt.rect shouldBe ranked.rect
        }

        test("the work budget decides: too little for the whole search keeps the ranked position and reports it") {
            val neighbour = "fremd" to box(x = 158f, y = 60f, w = 40f, h = 43f)
            val ranked = place(costs = off, index = index(neighbour))
            val starved = place(costs = LabelCostModel(portRepair = true, perRequestWorkBudget = 40L), index = index(neighbour))
            starved.rect shouldBe ranked.rect
            starved.repairBudgetShort shouldBe true
        }

        test("outside wins a tie against inside: the moved label is not inside the own part") {
            val neighbour = "fremd" to box(x = 158f, y = 60f, w = 40f, h = 43f)
            val after = place(costs = on, index = index(neighbour))
            // inside the own part means below the border line y = 105
            (after.rect.origin.y + after.rect.size.height <= 105f) shouldBe true
        }

        test("a work budget too small for the second pass: the ranked position stays, no crash") {
            val neighbour = "fremd" to box(x = 158f, y = 60f, w = 40f, h = 43f)
            val idx = index(neighbour)
            val before = place(costs = off, index = idx)
            val tight = LabelCostModel(portRepair = true, perRequestWorkBudget = 40L, totalWorkBudget = 40L)
            val after = LabelPlacer.place(requests = listOf(request()), index = idx, costs = tight).getValue("p")
            after.rect shouldBe before.rect
            partCollisions(r = after.rect, index = idx) shouldBe 1
        }
    })
