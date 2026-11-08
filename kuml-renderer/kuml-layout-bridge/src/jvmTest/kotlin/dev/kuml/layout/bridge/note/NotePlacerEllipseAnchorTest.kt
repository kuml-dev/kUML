package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun oneNode(
    id: String,
    r: Rect,
) = LayoutResult(
    engineId = LayoutEngineId("test"),
    seed = null,
    canvas = Size(width = 900f, height = 600f),
    nodes = mapOf(NodeId(id) to NodeLayout(bounds = r)),
    edges = emptyMap(),
    groups = emptyMap(),
)

private fun obstaclesOf(layout: LayoutResult): ObstacleIndex {
    val o = ObstacleIndex()
    layout.nodes.forEach { (id, n) -> o.addRect(kind = ObstacleKind.NODE, r = n.bounds, ownerId = id.value) }
    return o
}

/** Nacharbeit 42: an anchor on an ellipse ends on the outline; a rectangle anchor keeps its old behaviour. */
class NotePlacerEllipseAnchorTest :
    FunSpec({
        val bounds = box(x = 300f, y = 250f, w = 280f, h = 70f)
        val ellipse = AnchorGeometry.Ellipse(cx = bounds.cx, cy = bounds.cy, rx = bounds.size.width / 2f, ry = bounds.size.height / 2f)

        fun place(extras: Map<String, AnchorGeometry>): Pair<LayoutResult, List<Point>> {
            val layout = oneNode(id = "UC", r = bounds)
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes =
                        listOf(
                            NotePlacementInput(
                                noteId = "n",
                                body = "A note",
                                anchors = listOf(NoteAnchorRef(linkId = "link:n::UC", targetId = "UC")),
                            ),
                        ),
                    resolver = AnchorGeometryResolver(layout = layout, extras = extras),
                    obstacles = obstaclesOf(layout),
                )
            val route =
                r.layout.edges.entries
                    .first { it.key != EdgeId("UC") }
                    .value
                    .polylinePoints()
            return r.layout to route
        }

        test("the anchor line ends on the ellipse outline and arrives from outside") {
            val (_, route) = place(extras = mapOf("UC" to ellipse))
            val end = route.last()
            EllipseGeometry.residual(e = ellipse, p = end) shouldBeLessThanOrEqual 0.5f
            EllipseGeometry.leavesOutward(e = ellipse, q = end, prev = route[route.size - 2]) shouldBe true
        }

        test("the same node as a plain rectangle anchor still ends on the border of the bounding box") {
            val (_, route) = place(extras = emptyMap())
            val end = route.last()
            val onBorder =
                kotlin.math.abs(end.x - bounds.left) < 0.5f ||
                    kotlin.math.abs(end.x - bounds.right) < 0.5f ||
                    kotlin.math.abs(end.y - bounds.top) < 0.5f ||
                    kotlin.math.abs(end.y - bounds.bottom) < 0.5f
            onBorder shouldBe true
        }
    })
