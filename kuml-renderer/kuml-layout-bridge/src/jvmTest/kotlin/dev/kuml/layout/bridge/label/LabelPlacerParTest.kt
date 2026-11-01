package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** PAL wave 2C-b3: the cost-model extensions of the one label placer (wider search, node clearance, clean flag). */
class LabelPlacerParTest :
    FunSpec(body = {
        val line = listOf(Point(x = 100f, y = 0f), Point(x = 100f, y = 200f))
        val size = Size(width = 40f, height = 14f)

        fun request() = LabelRequest(id = "e", role = LabelRole.EDGE_CENTER, ownerId = "e", size = size, polyline = line)

        test("defaults: result is the one of the former fixed offsets (label 4 px beside the line, right side first)") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = line, ownerId = "e")
            val placed =
                LabelPlacer
                    .place(
                        requests = listOf(request()),
                        index = index,
                        costs = LabelCostModel(lineClearance = 2f),
                    ).getValue("e")
            placed.rect.origin.x shouldBe 104f
            placed.onLine shouldBe false
            placed.clean shouldBe true
        }

        test("offsets beyond 12 px are used when 4 to 12 px are taken") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = line, ownerId = "e")
            // fill both sides of the line from 3 px up to 14 px away, over the whole line
            index.addRect(kind = ObstacleKind.NODE, r = box(x = 103f, y = 0f, w = 11f, h = 200f), ownerId = "wall-r")
            index.addRect(kind = ObstacleKind.NODE, r = box(x = 86f, y = 0f, w = 11f, h = 200f), ownerId = "wall-l")
            val costs =
                LabelCostModel(
                    lineClearance = 2f,
                    nodeClearance = 2f,
                    edgeOffsets = listOf(4f, 8f, 12f, 16f, 20f, 24f),
                    maxDistancePx = 24f,
                )
            val placed = LabelPlacer.place(requests = listOf(request()), index = index, costs = costs).getValue("e")
            placed.clean shouldBe true
            // 16 px offset: label edge at 116, 2 px clear of the wall that ends at 114
            placed.rect.origin.x shouldBeGreaterThanOrEqual 116f
        }

        test("nodeClearance keeps 2 px from a node") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = line, ownerId = "e")
            index.addRect(kind = ObstacleKind.NODE, r = box(x = 140f, y = 0f, w = 50f, h = 200f), ownerId = "n")
            val costs = LabelCostModel(lineClearance = 2f, nodeClearance = 2f)
            val placed = LabelPlacer.place(requests = listOf(request()), index = index, costs = costs).getValue("e")
            val gap = 140f - (placed.rect.origin.x + placed.rect.size.width)
            // either left of the line, or right with at least 2 px to the node
            (placed.rect.origin.x < 100f || gap >= 2f) shouldBe true
        }

        test("clean is false when nothing is free and the label sits on its own line") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = line, ownerId = "e")
            index.addRect(kind = ObstacleKind.NODE, r = box(x = 0f, y = -50f, w = 400f, h = 300f).let { it }, ownerId = "everything")
            // a node that covers everything except the line itself is not expressible; cover both sides instead
            val costs = LabelCostModel(lineClearance = 2f)
            val placed = LabelPlacer.place(requests = listOf(request()), index = index, costs = costs).getValue("e")
            placed.clean shouldBe false
        }
    })
