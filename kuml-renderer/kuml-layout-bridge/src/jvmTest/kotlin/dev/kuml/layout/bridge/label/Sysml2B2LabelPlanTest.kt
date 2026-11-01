package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun r(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun Rect.overlaps(o: Rect) =
    origin.x < o.origin.x + o.size.width &&
        o.origin.x < origin.x + size.width &&
        origin.y < o.origin.y + o.size.height &&
        o.origin.y < origin.y + size.height

/** PAL wave 2C-b2: port side exclusion, arrow head obstacles and the wrap of port labels. */
class Sysml2B2LabelPlanTest :
    FunSpec(body = {
        test("a port label never uses the excluded side, even when that side is the cheapest") {
            val anchor = r(x = 100f, y = 100f, w = 12f, h = 12f)
            for (side in LabelSide.entries) {
                val out =
                    LabelPlacer.place(
                        requests =
                            listOf(
                                LabelRequest(
                                    id = "p",
                                    role = LabelRole.PORT,
                                    ownerId = "p",
                                    size = Size(width = 40f, height = 14f),
                                    anchorRect = anchor,
                                    excludedSide = side,
                                ),
                            ),
                        index = ObstacleIndex(),
                    )
                val l = out.getValue("p").rect
                val onExcluded =
                    when (side) {
                        LabelSide.NORTH -> l.origin.y + l.size.height <= anchor.origin.y + 0.01f
                        LabelSide.SOUTH -> l.origin.y >= anchor.origin.y + anchor.size.height - 0.01f
                        LabelSide.WEST -> l.origin.x + l.size.width <= anchor.origin.x + 0.01f
                        LabelSide.EAST -> l.origin.x >= anchor.origin.x + anchor.size.width - 0.01f
                    }
                onExcluded shouldBe false
            }
        }

        test("a label avoids the square around an arrow head") {
            val line = listOf(Point(x = 100f, y = 100f), Point(x = 300f, y = 100f))
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = line, ownerId = "e")
            val tip = Rect(origin = Point(x = 294f, y = 94f), size = Size(width = 12f, height = 12f))
            index.addRect(kind = ObstacleKind.ARROWHEAD, r = tip)
            val out =
                LabelPlacer.place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "e",
                                role = LabelRole.EDGE_CENTER,
                                ownerId = "e",
                                size = Size(width = 60f, height = 14f),
                                polyline = line,
                            ),
                        ),
                    index = index,
                )
            out.getValue("e").rect.overlaps(tip) shouldBe false
        }

        test("the default line clearance is 2 px") { LabelCostModel().lineClearance shouldBe 2f }

        test("a short port label stays on one line") {
            Sysml2LabelSizing.wrapPortLabel(text = "out : PowerPort") shouldBe listOf("out : PowerPort")
        }

        test("a long port label wraps to at most two lines and loses no character") {
            val text = "supplyVoltageMeasurement : ~HighVoltagePowerPortType"
            val lines = Sysml2LabelSizing.wrapPortLabel(text = text)
            lines shouldHaveSize 2
            lines.joinToString("").replace(" ", "") shouldBe text.replace(" ", "")
            (lines[0].length <= Sysml2LabelSizing.PORT_WRAP_CHARS) shouldBe true
            lines[1] shouldNotBe ""
        }

        test("the conjugation tilde survives the wrap") {
            val lines = Sysml2LabelSizing.wrapPortLabel(text = "aVeryLongPortNameThatNeedsWrapping : ~Conj")
            lines.joinToString(" ").contains("~Conj") shouldBe true
        }

        test("an unbreakable word is kept whole instead of being cut") {
            val word = "x".repeat(60)
            Sysml2LabelSizing.wrapPortLabel(text = word) shouldBe listOf(word)
        }
    })
