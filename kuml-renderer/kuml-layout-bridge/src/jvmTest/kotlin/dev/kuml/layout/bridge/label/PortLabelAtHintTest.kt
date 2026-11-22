package dev.kuml.layout.bridge.label

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

/** PAL Welle B: the `labelAt` hint (Before / After / Auto) steers the side of a port label; an unreachable hint is reported. */
class PortLabelAtHintTest :
    FunSpec(body = {
        val pin = rect(x = 100f, y = 100f, w = 10f, h = 10f)
        val size = Size(width = 40f, height = 14f)

        fun place(
            outward: LabelSide,
            tangent: Int,
            index: ObstacleIndex = ObstacleIndex(),
        ): PlacedLabel =
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
                                outward = outward,
                                parentId = "box",
                                preferredTangent = tangent,
                            ),
                        ),
                    index = index,
                ).getValue("p")

        test("north port: Before puts the label left of the square, After right of it, Auto keeps the default (right)") {
            val before = place(LabelSide.NORTH, tangent = -1)
            val after = place(LabelSide.NORTH, tangent = 1)
            val auto = place(LabelSide.NORTH, tangent = 0)
            (before.rect.origin.x + before.rect.size.width <= pin.origin.x) shouldBe true
            (after.rect.origin.x >= pin.origin.x + pin.size.width) shouldBe true
            auto.rect shouldBe after.rect
            before.hintMissed shouldBe false
            after.hintMissed shouldBe false
        }

        test("east port: Before = above the connector line, After = below") {
            val before = place(LabelSide.EAST, tangent = -1)
            val after = place(LabelSide.EAST, tangent = 1)
            (before.rect.origin.y + before.rect.size.height <= pin.origin.y + pin.size.height / 2f) shouldBe true
            (after.rect.origin.y >= pin.origin.y + pin.size.height / 2f) shouldBe true
            before.hintMissed shouldBe false
        }

        test("west port: Before = above, After = below") {
            val before = place(LabelSide.WEST, tangent = -1)
            val after = place(LabelSide.WEST, tangent = 1)
            (before.rect.origin.y < after.rect.origin.y) shouldBe true
            before.hintMissed shouldBe false
            after.hintMissed shouldBe false
        }

        test("an unreachable hint falls back to the free side and is flagged") {
            val index = ObstacleIndex()
            // Block the whole left side of the north port: every Before position collides.
            index.addRect(kind = ObstacleKind.NODE, r = rect(x = 0f, y = 60f, w = 99f, h = 50f), ownerId = "other")
            val placed = place(LabelSide.NORTH, tangent = -1, index = index)
            (placed.rect.origin.x >= pin.origin.x + pin.size.width) shouldBe true
            placed.hintMissed shouldBe true
        }

        test("without a hint nothing is ever flagged") {
            place(LabelSide.NORTH, tangent = 0).hintMissed shouldBe false
        }
    })
