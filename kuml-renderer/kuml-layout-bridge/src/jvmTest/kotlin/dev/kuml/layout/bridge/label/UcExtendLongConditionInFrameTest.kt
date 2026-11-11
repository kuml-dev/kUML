package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.bottom
import dev.kuml.layout.bridge.note.left
import dev.kuml.layout.bridge.note.right
import dev.kuml.layout.bridge.note.top
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

private fun rc(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/**
 * Nacharbeit 74 (a): a long `extend` condition (51 characters, wrapped to two to four lines depending on `wrapColumns`)
 * still lies inside its subject frame. Font independent geometry: the label sizes stand for the wrapped block of the
 * default wrap, `wrapColumns = 1` (narrowest) and `wrapColumns = 2`.
 */
class UcExtendLongConditionInFrameTest :
    FunSpec({
        // a narrow frame (about 207 px) with three use cases in a column and a C line from the third to the first
        val frame = rc(x = 0f, y = 0f, w = 207f, h = 420f)
        val u1 = rc(x = 20f, y = 30f, w = 140f, h = 60f)
        val u2 = rc(x = 20f, y = 160f, w = 140f, h = 60f)
        val u3 = rc(x = 20f, y = 290f, w = 140f, h = 60f)
        val spur = 178f
        val c =
            EdgeRoute.OrthogonalRounded(
                source = Point(x = 160f, y = 320f),
                target = Point(x = 160f, y = 60f),
                waypoints = listOf(Point(x = spur, y = 320f), Point(x = spur, y = 60f)),
                cornerRadiusPx = 8f,
            )
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 0L,
                canvas = Size(width = 207f, height = 420f),
                nodes =
                    linkedMapOf(
                        NodeId("u1") to NodeLayout(bounds = u1),
                        NodeId("u2") to NodeLayout(bounds = u2),
                        NodeId("u3") to NodeLayout(bounds = u3),
                    ),
                edges = linkedMapOf(EdgeId("x") to c),
                groups = linkedMapOf(GroupId("f") to GroupLayout(bounds = frame)),
            )
        val variants =
            mapOf(
                "default wrap" to Size(width = 146f, height = 64f),
                "wrapColumns = 1" to Size(width = 112f, height = 96f),
                "wrapColumns = 2" to Size(width = 170f, height = 52f),
            )
        variants.forEach { (name, size) ->
            test("long condition, $name: the label is inside the frame with at least 4 px to every edge") {
                val placed = Sysml2DiagramLabelPlan.compute(layout = layout, edgeLabelSizes = mapOf("x" to size), keepInsideFrames = true)
                val r = placed.getValue("x").rect
                r.size shouldBe size
                r.left shouldBeGreaterThanOrEqualTo frame.left + 4f
                r.top shouldBeGreaterThanOrEqualTo frame.top + 4f
                r.right shouldBeLessThanOrEqual frame.right - 4f
                r.bottom shouldBeLessThanOrEqual frame.bottom - 4f
            }
        }
    })
