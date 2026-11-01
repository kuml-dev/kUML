package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** PAL wave 2C-b2b: the fixed ranking of port and pin label positions (four outside, then four inside). */
class PortLabelRankingTest :
    FunSpec(body = {
        val pin = rect(x = 100f, y = 100f, w = 10f, h = 10f)
        val size = Size(width = 40f, height = 14f)

        fun place(
            index: ObstacleIndex,
            excluded: LabelSide? = null,
        ): Rect =
            LabelPlacer
                .place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "p",
                                role = LabelRole.PORT,
                                ownerId = "p",
                                size = size,
                                anchorRect = pin,
                                excludedSide = excluded,
                                outward = LabelSide.NORTH,
                                parentId = "box",
                            ),
                        ),
                    index = index,
                ).getValue("p")
                .rect

        test("free space: clockwise behind the pin, 4 px from it, outside the box border") {
            val r = place(ObstacleIndex())
            r.origin.x shouldBe (pin.origin.x + pin.size.width + LabelPlacer.PORT_LABEL_GAP_PX)
            (r.origin.y + r.size.height) shouldBe (pin.origin.y + pin.size.height / 2f - 2f)
        }

        test("clockwise taken: counter clockwise behind the pin") {
            val index = ObstacleIndex()
            index.addRect(kind = ObstacleKind.NODE, r = rect(x = 114f, y = 70f, w = 60f, h = 40f), ownerId = "other")
            val r = place(index)
            (r.origin.x + r.size.width) shouldBe (pin.origin.x - LabelPlacer.PORT_LABEL_GAP_PX)
        }

        test("the side where the own edge docks gets no straight-away candidate") {
            val index = ObstacleIndex()
            // both beside positions are blocked: the next free outside position would be straight above the pin
            index.addRect(kind = ObstacleKind.NODE, r = rect(x = 114f, y = 70f, w = 60f, h = 40f), ownerId = "a")
            index.addRect(kind = ObstacleKind.NODE, r = rect(x = 30f, y = 70f, w = 66f, h = 40f), ownerId = "b")
            val r = place(index, excluded = LabelSide.NORTH)
            // not above the pin on its docking side: the label is inside the box (below the border line)
            r.origin.y shouldBeGreaterThanOrEqual (pin.origin.y + pin.size.height / 2f)
        }

        test("inside only when every outside position collides hard") {
            val index = ObstacleIndex()
            index.addRect(kind = ObstacleKind.NODE, r = rect(x = 0f, y = 0f, w = 300f, h = 104f), ownerId = "other")
            val r = place(index)
            r.origin.y shouldBeGreaterThanOrEqual (pin.origin.y + pin.size.height / 2f)
        }
    })
