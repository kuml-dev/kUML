package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.layout.bridge.note.Sysml2NotePlacement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun r(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun Rect.hits(o: Rect) =
    origin.x < o.origin.x + o.size.width &&
        o.origin.x < origin.x + size.width &&
        origin.y < o.origin.y + o.size.height &&
        o.origin.y < origin.y + size.height

/** Two boxes with a vertical connector between them; a third box sits right of the connector's midpoint. */
private fun layout(blocker: Boolean): LayoutResult {
    val nodes = LinkedHashMap<NodeId, NodeLayout>()
    nodes[NodeId("a")] = NodeLayout(bounds = r(x = 100f, y = 0f, w = 120f, h = 60f))
    nodes[NodeId("b")] = NodeLayout(bounds = r(x = 100f, y = 300f, w = 120f, h = 60f))
    if (blocker) nodes[NodeId("c")] = NodeLayout(bounds = r(x = 166f, y = 150f, w = 120f, h = 60f))
    return LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = null,
        canvas = Size(width = 400f, height = 400f),
        nodes = nodes,
        edges = mapOf(EdgeId("e1") to EdgeRoute.Direct(source = Point(x = 160f, y = 60f), target = Point(x = 160f, y = 300f))),
        groups = emptyMap(),
    )
}

class Sysml2EdgeLabelPlanTest :
    FunSpec({
        val size = Size(width = 150f, height = 19f)

        test("the label of a connector keeps clear of every node and sits beside its line") {
            val l = layout(blocker = true)
            val placed = Sysml2EdgeLabelPlan.compute(layout = l, labelSizes = mapOf("e1" to size)).getValue("e1")
            l.nodes.values.forEach { placed.rect.hits(it.bounds) shouldBe false }
            (
                LabelPlacer.rectDistanceToPolyline(
                    r = placed.rect,
                    pts = listOf(Point(x = 160f, y = 60f), Point(x = 160f, y = 300f)),
                ) <= 12f
            ) shouldBe
                true
        }

        test("a note placed afterwards avoids the planned label rectangle") {
            val l = layout(blocker = false)
            val placed = Sysml2EdgeLabelPlan.compute(layout = l, labelSizes = mapOf("e1" to size)).getValue("e1")
            val index = Sysml2NotePlacement.obstaclesOf(layout = l, placedLabels = mapOf("e1" to placed.rect))
            // the planned rectangle is registered as the edge label, not an estimate at the midpoint
            index.rects.single { it.kind == ObstacleKind.EDGE_LABEL }.core shouldBe placed.rect
            index.rects.count { it.kind == ObstacleKind.EDGE_LABEL } shouldBe 1
        }

        test("an edge without a label size gets no plan and the plan is deterministic") {
            val l = layout(blocker = true)
            Sysml2EdgeLabelPlan.compute(layout = l, labelSizes = emptyMap()) shouldBe emptyMap()
            val a = Sysml2EdgeLabelPlan.compute(layout = l, labelSizes = mapOf("e1" to size))
            val b = Sysml2EdgeLabelPlan.compute(layout = l, labelSizes = mapOf("e1" to size))
            a shouldBe b
        }

        test("the size estimate is one function: width grows with the longest line, height with the line count") {
            val one = Sysml2LabelSizing.sizeOf(listOf("abc"))
            val two = Sysml2LabelSizing.sizeOf(listOf("abc", "abcdef"))
            (two.width > one.width) shouldBe true
            (two.height > one.height) shouldBe true
        }
    })
