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

/** Nacharbeit 54: an `extend` label stays inside its subject frame, clear of the border (font independent geometry). */
class UcExtendLabelInFrameTest :
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
        val size = Size(width = 146f, height = 64f)

        test("the label lies fully inside the frame, 4 px clear of every border line") {
            val placed = Sysml2DiagramLabelPlan.compute(layout = layout, edgeLabelSizes = mapOf("x" to size), keepInsideFrames = true)
            val r = placed.getValue("x").rect
            r.left shouldBeGreaterThanOrEqualTo frame.left + 4f
            r.top shouldBeGreaterThanOrEqualTo frame.top + 4f
            r.right shouldBeLessThanOrEqual frame.right - 4f
            r.bottom shouldBeLessThanOrEqual frame.bottom - 4f
        }

        test("a label wider than the frame cannot be kept inside and is reported as not clean") {
            val wide = Size(width = 300f, height = 40f)
            val placed = Sysml2DiagramLabelPlan.compute(layout = layout, edgeLabelSizes = mapOf("x" to wide), keepInsideFrames = true)
            placed.getValue("x").clean shouldBe false
        }

        test("a line that does not run inside a frame is not constrained") {
            val loose =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 400f, y = 10f),
                    target = Point(x = 400f, y = 200f),
                    waypoints = emptyList(),
                    cornerRadiusPx = 0f,
                )
            val l = layout.copy(edges = linkedMapOf(EdgeId("x") to loose))
            val withFlag = Sysml2DiagramLabelPlan.compute(layout = l, edgeLabelSizes = mapOf("x" to size), keepInsideFrames = true)
            val without = Sysml2DiagramLabelPlan.compute(layout = l, edgeLabelSizes = mapOf("x" to size))
            withFlag shouldBe without
        }

        test("without the flag the plan is the one of before (no frame logic)") {
            val a = Sysml2DiagramLabelPlan.compute(layout = layout, edgeLabelSizes = mapOf("x" to size))
            val b = Sysml2DiagramLabelPlan.compute(layout = layout, edgeLabelSizes = mapOf("x" to size), keepInsideFrames = false)
            a shouldBe b
        }

        test("FrameKeeper.clamp moves a label into the region and keeps its size") {
            val allowed = FrameKeeper.allowedRegion(frame)
            val out = FrameKeeper.clamp(r = rc(x = -50f, y = 500f, w = 100f, h = 30f), allowed = allowed)
            out.size shouldBe Size(width = 100f, height = 30f)
            FrameKeeper.inside(r = out, allowed = allowed) shouldBe true
        }
    })
