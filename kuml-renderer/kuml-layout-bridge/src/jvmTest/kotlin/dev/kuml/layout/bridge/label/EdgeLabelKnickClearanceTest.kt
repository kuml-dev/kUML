package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import kotlin.math.max

/** PAL Welle B (7): an IBD connector label block keeps 6 px from every bend of its line when a free spot exists. */
class EdgeLabelKnickClearanceTest :
    FunSpec(body = {
        // Longest segment (0,0)-(100,0), then a bend at (100,0) turning down.
        val line = listOf(Point(x = 0f, y = 0f), Point(x = 100f, y = 0f), Point(x = 100f, y = 60f))
        val knick = line[1]
        val block = Size(width = 100f, height = 30f)

        fun gapToKnick(r: Rect): Float {
            val dx = max(max(r.origin.x - knick.x, 0f), knick.x - (r.origin.x + r.size.width))
            val dy = max(max(r.origin.y - knick.y, 0f), knick.y - (r.origin.y + r.size.height))
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        fun place(costs: LabelCostModel): PlacedLabel =
            LabelPlacer
                .place(
                    requests =
                        listOf(LabelRequest(id = "e", role = LabelRole.EDGE_CENTER, ownerId = "e", size = block, polyline = line)),
                    index = ObstacleIndex(),
                    costs = costs,
                ).getValue("e")

        test("without the clearance the centred block crowds the bend") {
            gapToKnick(place(LabelCostModel(lineClearance = 2f)).rect) shouldBeLessThan 6f
        }

        test("with the clearance the block slides away from the bend (at least 6 px)") {
            gapToKnick(place(LabelCostModel(lineClearance = 2f, knickClearance = 6f)).rect) shouldBeGreaterThanOrEqual 6f
        }
    })
