package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.bottom
import dev.kuml.layout.bridge.note.left
import dev.kuml.layout.bridge.note.right
import dev.kuml.layout.bridge.note.top
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun r(
    x: Float,
    y: Float,
    w: Float = 100f,
    h: Float = 40f,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private object NoEngine : KumlLayoutEngine {
    override val id = LayoutEngineId("none")
    override val capabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds = setOf(DiagramKind.UmlUseCase),
            supportedEdgeStyles = setOf(EdgeRouteStyle.OrthogonalRounded),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 100,
        )

    override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult = error("not used")
}

/** The corridor and C line rules of the use case frame grid: spurs stay clear of the frame border. */
class UcFrameGridTest :
    FunSpec({
        val all = setOf(NodeId("s"), NodeId("m"), NodeId("t"))
        val s = r(x = 0f, y = 0f)
        val m = r(x = 0f, y = 60f)
        val t = r(x = 0f, y = 120f)
        val base = mapOf(NodeId("s") to s, NodeId("m") to m, NodeId("t") to t)
        val self = setOf(NodeId("s"), NodeId("t"))

        fun detour(
            nodes: Map<NodeId, Rect>,
            frames: List<Rect> = emptyList(),
        ) = UcFrameGrid.columnDetour(s = s, t = t, self = self, nodes = nodes, useCaseIds = nodes.keys, frames = frames)

        test("columnDetour: the east gutter is preferred when it is free") {
            detour(base) shouldBe (true to 118f)
        }

        test("columnDetour: the west gutter is used when the east one is blocked") {
            detour(base + (NodeId("x") to r(x = 105f, y = 50f))) shouldBe (false to -18f)
        }

        test("columnDetour: no C line when both gutters are blocked") {
            detour(base + (NodeId("x") to r(x = 105f, y = 50f)) + (NodeId("y") to r(x = -60f, y = 50f, w = 55f))) shouldBe null
        }

        test("columnDetour: no C line for neighbours or for different columns") {
            UcFrameGrid.columnDetour(
                s = s,
                t = m,
                self = setOf(NodeId("s"), NodeId("m")),
                nodes = base,
                useCaseIds = all,
            ) shouldBe null
            UcFrameGrid.columnDetour(s = s, t = r(x = 50f, y = 120f), self = self, nodes = base, useCaseIds = all) shouldBe null
        }

        test("columnDetour: a spur closer than 8 px to the frame border is refused, the other side is taken") {
            // frame padding 24 on the east: the spur at +18 would sit 6 px from the border
            val frame = r(x = -100f, y = -24f, w = 224f, h = 208f)
            detour(base, listOf(frame)) shouldBe (false to -18f)
        }

        test("columnDetour: no C line when both spurs would sit on the frame border") {
            detour(base, listOf(r(x = -24f, y = -24f, w = 148f, h = 208f))) shouldBe null
        }

        // 6 use cases, 2 columns: Alpha Beta / Gamma Delta / Epsilon Zeta; Beta and Zeta stand in the right column with Delta
        // between them, the extends in both directions run as C lines
        fun frameGraph(): LayoutGraph {
            val gid = GroupId("frame")
            val names = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta")
            val nodes =
                names.map { LayoutNode(id = NodeId(it), intrinsicSize = Size(width = 140f, height = 60f), groupId = gid) } +
                    LayoutNode(id = NodeId("actor"), intrinsicSize = Size(width = 40f, height = 80f), hints = NodeHints(rail = true))

            fun edge(
                id: String,
                a: String,
                b: String,
            ) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(a)), target = EndpointRef(nodeId = NodeId(b)))
            return LayoutGraph(
                nodes = nodes,
                edges =
                    listOf(
                        edge("e1", "actor", "alpha"),
                        edge("e2", "zeta", "beta"),
                        edge("e3", "beta", "zeta"),
                        edge("e4", "gamma", "epsilon"),
                    ),
                groups = listOf(LayoutGroup(id = gid, padding = Insets(top = 24f, right = 24f, bottom = 24f, left = 24f))),
            )
        }

        test("regression: no line runs on or across the subject frame border (C lines in the outer column)") {
            val g = frameGraph()
            val res = UcFrameGrid.layout(engine = NoEngine, graph = g, hints = LayoutHints.DEFAULT, spec = LayoutSpec(wrapColumns = 2))
            val frame = res.groups.getValue(GroupId("frame")).bounds
            for ((id, route) in res.edges) {
                val pts = route.polylinePoints()
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val vertical = abs(a.x - b.x) < 0.01f
                    val horizontal = abs(a.y - b.y) < 0.01f
                    if (vertical == horizontal) continue
                    if (vertical) {
                        val overlaps = max(a.y, b.y) > frame.top && min(a.y, b.y) < frame.bottom
                        if (overlaps) {
                            abs(a.x - frame.left) shouldBeGreaterThanOrEqualTo 3.9f
                            abs(a.x - frame.right) shouldBeGreaterThanOrEqualTo 3.9f
                            // never crosses the border along its length
                            ((a.x > frame.left && a.x < frame.right) || a.x < frame.left || a.x > frame.right) shouldBe true
                        }
                    } else {
                        val overlaps = max(a.x, b.x) > frame.left && min(a.x, b.x) < frame.right
                        if (overlaps) {
                            abs(a.y - frame.top) shouldBeGreaterThanOrEqualTo 3.9f
                            abs(a.y - frame.bottom) shouldBeGreaterThanOrEqualTo 3.9f
                        }
                    }
                }
                id shouldNotBe null
            }
        }
    })
