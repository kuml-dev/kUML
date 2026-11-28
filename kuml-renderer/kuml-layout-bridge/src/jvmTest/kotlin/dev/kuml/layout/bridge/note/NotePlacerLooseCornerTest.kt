@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

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
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun r(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** Content box 0..400 x 0..300 inside a canvas of 500 x 400 (padding); free bottom-right quadrant unless [extra] fills it. */
private fun layoutOf(
    extra: Map<String, Rect> = emptyMap(),
    edges: Map<String, EdgeRoute> = emptyMap(),
    groups: Map<String, Rect> = emptyMap(),
) = LayoutResult(
    engineId = LayoutEngineId("test"),
    seed = null,
    canvas = Size(width = 500f, height = 400f),
    nodes =
        (
            mapOf(
                "P" to r(0f, 0f, 100f, 100f),
                "Q" to r(300f, 0f, 100f, 100f),
                "S" to r(0f, 200f, 100f, 100f),
            ) + extra
        ).entries.associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
    edges = edges.entries.associate { (k, v) -> EdgeId(k) to v },
    groups = groups.entries.associate { (k, v) -> GroupId(k) to GroupLayout(bounds = v) },
)

private fun measure(
    w: Float,
    h: Float,
) = object : NoteMeasure {
    override fun measure(
        body: String,
        maxWidth: Float,
    ): Size = Size(width = w, height = h)
}

private fun obstaclesOf(layout: LayoutResult): ObstacleIndex {
    val o = ObstacleIndex()
    layout.nodes.forEach { (id, n) -> o.addRect(kind = ObstacleKind.NODE, r = n.bounds, ownerId = id.value) }
    layout.edges.forEach { (id, e) -> o.addPolyline(kind = ObstacleKind.EDGE, pts = e.polylinePoints(), ownerId = id.value) }
    return o
}

private fun place(
    layout: LayoutResult,
    ids: List<String> = listOf("n"),
    w: Float = 100f,
    h: Float = 40f,
    config: NotePlacementConfig = NotePlacementConfig(looseNotesBelow = true),
) = NotePlacer.place(
    layout = layout,
    notes = ids.map { NotePlacementInput(noteId = it, body = "Legend", anchors = emptyList()) },
    resolver = AnchorGeometryResolver(layout),
    obstacles = obstaclesOf(layout),
    measure = measure(w, h),
    config = config,
)

private fun NotePlacementResult.rectOf(id: String): Rect = layout.nodes.getValue(NodeId(id)).bounds

class NotePlacerLooseCornerTest :
    FunSpec({
        test("a free corner takes the note flush with the content box and leaves the canvas unchanged") {
            val src = layoutOf()
            val res = place(src)
            res.rectOf("n") shouldBe r(300f, 260f, 100f, 40f)
            res.layout.canvas shouldBe src.canvas
            res.dx shouldBe 0f
            res.dy shouldBe 0f
        }

        test("a node in the corner moves the note left in 8 px steps (candidate A first)") {
            val res = place(layoutOf(extra = mapOf("C" to r(330f, 230f, 60f, 60f))))
            // right edge must stay 12 px from C (x = 330): 330 - 12 - 100 = 218; first step on the 8 px raster at or left of it = 212
            res.rectOf("n") shouldBe r(212f, 260f, 100f, 40f)
        }

        test("an edge in the corner moves the note left of it") {
            val edge = mapOf("e" to EdgeRoute.Direct(source = Point(x = 380f, y = 100f), target = Point(x = 380f, y = 300f)))
            val res = place(layoutOf(edges = edge))
            // a segment touching the 12 px zone counts as a violation: 268 + 100 + 12 = 380 touches, so the next raster step (260) wins
            res.rectOf("n") shouldBe r(260f, 260f, 100f, 40f)
        }

        test("a note stays below the content when every candidate is blocked") {
            val res = place(layoutOf(extra = mapOf("W" to r(150f, 150f, 250f, 150f))))
            // old behaviour: BELOW the extent (canvas 400 high) + 2/3 candidateGap, right-aligned to the extent (canvas 500 wide)
            res.rectOf("n") shouldBe r(400f, 416f, 100f, 40f)
        }

        test("a grid wider than half the content box keeps the old place") {
            val res = place(layoutOf(), w = 250f)
            res.rectOf("n").origin.y shouldBe 416f
        }

        test("an exhausted budget keeps the old place") {
            val res = place(layoutOf(), config = NotePlacementConfig(looseNotesBelow = true, workBudget = 0L))
            res.rectOf("n").origin.y shouldBe 416f
        }

        test("two free notes form one block in the corner") {
            val res = place(layoutOf(), ids = listOf("a", "b"), w = 90f)
            res.rectOf("a") shouldBe r(204f, 260f, 90f, 40f)
            res.rectOf("b") shouldBe r(310f, 260f, 90f, 40f)
        }

        test("a block that does not fit keeps both notes at the old place (all or nothing)") {
            val res = place(layoutOf(), ids = listOf("a", "b"), w = 110f)
            res.rectOf("a").origin.y shouldBe 416f
            res.rectOf("b").origin.y shouldBe 416f
        }

        test("with lanes the corner sits 16 px inside the lane") {
            val lanes = mapOf("L1" to r(0f, 0f, 200f, 300f), "L2" to r(200f, 0f, 200f, 300f))
            val res = place(layoutOf(groups = lanes))
            res.rectOf("n") shouldBe r(284f, 244f, 100f, 40f)
        }

        test("a corner that crosses a lane border is dropped for the next free candidate inside one lane") {
            val lanes = mapOf("L1" to r(0f, 0f, 300f, 300f), "L2" to r(300f, 0f, 100f, 300f))
            val res = place(layoutOf(groups = lanes))
            val n = res.rectOf("n")
            n shouldBe r(180f, 244f, 100f, 40f)
            (n.origin.x + n.size.width <= 300f - 16f) shouldBe true
        }

        test("placement is deterministic") {
            place(layoutOf()).rectOf("n") shouldBe place(layoutOf()).rectOf("n")
        }

        test("without looseNotesBelow (not an activity diagram) the grid stays right of the content") {
            val res = place(layoutOf(), config = NotePlacementConfig())
            res.rectOf("n") shouldBe r(524f, 0f, 100f, 40f)
        }
    })
