package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.util.BasicProgressMonitor
import kotlin.math.abs

/**
 * Nacharbeit 106 spike: does ELK 0.12 honour a per-compound `DIRECTION` when that compound sets
 * `HIERARCHY_HANDLING = SEPARATE_CHILDREN` while the root uses `INCLUDE_CHILDREN`? The test drives the ELK graph
 * directly (no production API) and records the observed behaviour.
 */
class GroupDirectionSpikeTest :
    FunSpec({
        val tol = 0.5f
        val g = GroupId("G")

        fun node(
            id: String,
            inGroup: Boolean,
        ) = LayoutNode(id = NodeId(id), intrinsicSize = Size(width = 80f, height = 40f), groupId = if (inGroup) g else null)

        fun edge(
            id: String,
            s: String,
            t: String,
        ) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(s)), target = EndpointRef(nodeId = NodeId(t)))

        fun run(
            graph: LayoutGraph,
            separate: Boolean,
        ): LayoutResult {
            val builder = ElkGraphBuilder(graph)
            val root = builder.build()
            val hints = LayoutHints(direction = LayoutDirection.TopToBottom)
            HintsMapper.applyGlobalHints(root = root, hints = hints, config = ElkEngineConfiguration.DEFAULT)
            HintsMapper.applyGroupPadding(builder = builder, hints = hints, config = ElkEngineConfiguration.DEFAULT)
            if (separate) {
                val elkG = builder.groupMap.getValue(g)
                elkG.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
                elkG.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
            }
            RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
            return ResultMapper.toLayoutResult(
                engineId = ElkLayoutEngine().id,
                builder = builder,
                root = root,
                warnings = emptyList(),
                nodeCount = graph.nodes.size,
            )
        }

        fun points(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.Bezier -> listOf(r.source) + r.controlPoints + listOf(r.target)
            }

        fun inside(
            r: Rect,
            p: Point,
        ) = p.x >= r.origin.x - tol &&
            p.x <= r.origin.x + r.size.width + tol &&
            p.y >= r.origin.y - tol &&
            p.y <= r.origin.y + r.size.height + tol

        val group =
            LayoutGroup(id = g, padding = Insets(top = 36f, right = 20f, bottom = 20f, left = 20f), layoutAsCompound = true)
        val inner =
            LayoutGraph(
                nodes = listOf(node("A", true), node("B", true), node("C", true), node("X", false)),
                edges = listOf(edge("ab", "A", "B"), edge("bc", "B", "C")),
                groups = listOf(group),
            )

        test("self-contained group: members follow the group direction and stay inside the frame") {
            val r = run(inner, separate = true)
            val a = r.nodes.getValue(NodeId("A")).bounds
            val b = r.nodes.getValue(NodeId("B")).bounds
            val c = r.nodes.getValue(NodeId("C")).bounds
            (a.origin.x < b.origin.x) shouldBe true
            (b.origin.x < c.origin.x) shouldBe true
            (abs(a.origin.y - b.origin.y) < tol && abs(b.origin.y - c.origin.y) < tol) shouldBe true
            val gb = r.groups.getValue(g).bounds
            for (m in listOf(a, b, c)) {
                inside(gb, m.origin) shouldBe true
                inside(gb, Point(x = m.origin.x + m.size.width, y = m.origin.y + m.size.height)) shouldBe true
            }
            for (id in listOf("ab", "bc")) points(r.edges.getValue(EdgeId(id))).forEach { inside(gb, it) shouldBe true }
            val x = r.nodes.getValue(NodeId("X")).bounds
            val overlap =
                x.origin.x < gb.origin.x + gb.size.width &&
                    x.origin.x + x.size.width > gb.origin.x &&
                    x.origin.y < gb.origin.y + gb.size.height &&
                    x.origin.y + x.size.height > gb.origin.y
            overlap shouldBe false
        }

        test("baseline without the property keeps the root direction") {
            val r = run(inner, separate = false)
            val a = r.nodes.getValue(NodeId("A")).bounds
            val b = r.nodes.getValue(NodeId("B")).bounds
            (a.origin.y < b.origin.y) shouldBe true
        }

        test("crossing edge: ELK throws under SEPARATE_CHILDREN, so the property must never be set for such groups") {
            val crossing = inner.copy(edges = inner.edges + edge("xa", "X", "A"))
            shouldThrow<org.eclipse.elk.core.UnsupportedGraphException> { run(crossing, separate = true) }
            // Without the property the same graph lays out fine.
            run(crossing, separate = false).edges.containsKey(EdgeId("xa")) shouldBe true
        }
    })
