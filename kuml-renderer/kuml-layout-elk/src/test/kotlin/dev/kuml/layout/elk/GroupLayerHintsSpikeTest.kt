package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayerPin
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.util.BasicProgressMonitor
import kotlin.math.abs

/**
 * Nacharbeit 107 spike: does ELK 0.12 honour member layer hints (partitions, layer constraints) inside a compound that
 * lays out its children separately (`SEPARATE_CHILDREN`) while the root uses `INCLUDE_CHILDREN`? Drives the ELK graph
 * directly (no production API) and pins the observed behaviour.
 */
class GroupLayerHintsSpikeTest :
    FunSpec({
        val tol = 0.5f
        val g = GroupId("G")

        fun node(
            id: String,
            inGroup: Boolean = true,
            hints: NodeHints = NodeHints(),
        ) = LayoutNode(
            id = NodeId(id),
            intrinsicSize = Size(width = 80f, height = 40f),
            groupId = if (inGroup) g else null,
            hints = hints,
        )

        fun edge(
            id: String,
            s: String,
            t: String,
        ) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(s)), target = EndpointRef(nodeId = NodeId(t)))

        fun run(
            graph: LayoutGraph,
            separate: Boolean,
            rootPartitioning: Boolean = false,
        ): LayoutResult {
            val builder = ElkGraphBuilder(graph)
            val root = builder.build()
            val hints = LayoutHints(direction = LayoutDirection.TopToBottom)
            HintsMapper.applyGlobalHints(root = root, hints = hints, config = ElkEngineConfiguration.DEFAULT)
            HintsMapper.applyGroupPadding(builder = builder, hints = hints, config = ElkEngineConfiguration.DEFAULT)
            if (rootPartitioning) {
                root.setProperty(CoreOptions.PARTITIONING_ACTIVATE, true)
                root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.SIMPLE)
                root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, false)
            }
            if (separate) {
                val elkG = builder.groupMap.getValue(g)
                elkG.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
                elkG.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
                elkG.setProperty(CoreOptions.PARTITIONING_ACTIVATE, true)
                elkG.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.SIMPLE)
                elkG.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, false)
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

        fun onBorder(
            r: Rect,
            p: Point,
        ): Boolean {
            if (!inside(r, p)) return false
            val x0 = r.origin.x
            val x1 = r.origin.x + r.size.width
            val y0 = r.origin.y
            val y1 = r.origin.y + r.size.height
            return abs(p.x - x0) < tol || abs(p.x - x1) < tol || abs(p.y - y0) < tol || abs(p.y - y1) < tol
        }

        fun y(
            r: LayoutResult,
            id: String,
        ) = r.nodes
            .getValue(NodeId(id))
            .bounds.origin.y

        fun x(
            r: LayoutResult,
            id: String,
        ) = r.nodes
            .getValue(NodeId(id))
            .bounds.origin.x

        fun sameY(
            r: LayoutResult,
            a: String,
            b: String,
        ) = abs(y(r, a) - y(r, b)) < tol

        val group =
            LayoutGroup(id = g, padding = Insets(top = 36f, right = 20f, bottom = 20f, left = 20f), layoutAsCompound = true)

        fun assertGeometry(
            r: LayoutResult,
            graph: LayoutGraph,
        ) {
            val gb = r.groups.getValue(g).bounds
            for (n in graph.nodes.filter { it.groupId == g }) {
                val b = r.nodes.getValue(n.id).bounds
                inside(gb, b.origin) shouldBe true
                inside(gb, Point(x = b.origin.x + b.size.width, y = b.origin.y + b.size.height)) shouldBe true
            }
            for (e in graph.edges) {
                val route = r.edges.getValue(e.id)
                val pts = points(route)
                val s = r.nodes[e.source.nodeId]?.bounds ?: r.groups.getValue(GroupId(e.source.nodeId.value)).bounds
                val t = r.nodes[e.target.nodeId]?.bounds ?: r.groups.getValue(GroupId(e.target.nodeId.value)).bounds
                onBorder(s, pts.first()) shouldBe true
                onBorder(t, pts.last()) shouldBe true
                if (e.source.nodeId.value != "X" && e.target.nodeId.value != "X") pts.forEach { inside(gb, it) shouldBe true }
            }
            val xb = r.nodes.getValue(NodeId("X")).bounds
            val overlap =
                xb.origin.x < gb.origin.x + gb.size.width &&
                    xb.origin.x + xb.size.width > gb.origin.x &&
                    xb.origin.y < gb.origin.y + gb.size.height &&
                    xb.origin.y + xb.size.height > gb.origin.y
            overlap shouldBe false
        }

        fun part(i: Int) = NodeHints(layerIndex = i)

        val ids = listOf("A", "B", "C", "D", "E", "F")
        val wrapNodes = ids.mapIndexed { i, id -> node(id, hints = part(i / 2)) } + node("X", inGroup = false)
        val wrapGraph = LayoutGraph(nodes = wrapNodes, edges = emptyList(), groups = listOf(group))

        test("1 baseline: INCLUDE_CHILDREN ignores partitions inside a compound") {
            val plain = run(wrapGraph.copy(nodes = wrapNodes.map { it.copy(hints = NodeHints()) }), separate = false)
            val hinted = run(wrapGraph, separate = false)
            for (id in ids) {
                hinted.nodes.getValue(NodeId(id)).bounds shouldBe plain.nodes.getValue(NodeId(id)).bounds
            }
        }

        test("2 wrap: SEPARATE_CHILDREN + partitions gives rows of two") {
            val r = run(wrapGraph, separate = true)
            sameY(r, "A", "B") shouldBe true
            sameY(r, "C", "D") shouldBe true
            sameY(r, "E", "F") shouldBe true
            (y(r, "A") < y(r, "C")) shouldBe true
            (y(r, "C") < y(r, "E")) shouldBe true
            assertGeometry(r, wrapGraph)
        }

        test("3 sameLayer: A and C share a row in a chain") {
            val nodes =
                listOf(node("A", hints = part(0)), node("B", hints = part(1)), node("C", hints = part(0)), node("X", inGroup = false))
            val graph = LayoutGraph(nodes = nodes, edges = listOf(edge("ab", "A", "B"), edge("bc", "B", "C")), groups = listOf(group))
            val r = run(graph, separate = true)
            sameY(r, "A", "C") shouldBe true
            assertGeometry(r, graph)
        }

        test("4 first/last: pins land on the extreme rows") {
            val nodes =
                listOf(
                    node("A"),
                    node("B"),
                    node("C"),
                    node("D", hints = NodeHints(layerPin = LayerPin.First)),
                    node("E", hints = NodeHints(layerPin = LayerPin.Last)),
                    node("X", inGroup = false),
                )
            val graph = LayoutGraph(nodes = nodes, edges = listOf(edge("ab", "A", "B"), edge("bc", "B", "C")), groups = listOf(group))
            val r = run(graph, separate = true)
            for (id in listOf("A", "B", "C", "E")) (y(r, "D") <= y(r, id) + tol) shouldBe true
            (y(r, "D") < y(r, "C")) shouldBe true
            for (id in listOf("A", "B", "C", "D")) (y(r, "E") >= y(r, id) - tol) shouldBe true
            (y(r, "E") > y(r, "A")) shouldBe true
            assertGeometry(r, graph)
        }

        test("4b first/last combined with partitions, as withSpec produces them") {
            val nodes =
                listOf(
                    node("A", hints = NodeHints(layerIndex = 3)),
                    node("B", hints = part(1)),
                    node("C", hints = part(2)),
                    node("D", hints = part(0)),
                    node("X", inGroup = false),
                )
            val graph = LayoutGraph(nodes = nodes, edges = listOf(edge("bc", "B", "C")), groups = listOf(group))
            val r = run(graph, separate = true)
            (y(r, "D") < y(r, "B")) shouldBe true
            (y(r, "C") < y(r, "A")) shouldBe true
            assertGeometry(r, graph)
        }

        test("6 sibling anchor edge X to the compound anchor") {
            val nodes = ids.take(2).map { node(it, hints = part(0)) } + node("X", inGroup = false)
            val graph = LayoutGraph(nodes = nodes, edges = listOf(edge("xg", "X", "G"), edge("ab", "A", "B")), groups = listOf(group))
            val r = run(graph, separate = true)
            assertGeometry(r, graph)
        }

        test("7 root partitioning together with a separate compound") {
            val nodes =
                listOf(
                    node("P", inGroup = false, hints = part(0)),
                    node("Q", inGroup = false, hints = part(1)),
                    node("X", inGroup = false, hints = part(2)),
                ) + ids.mapIndexed { i, id -> node(id, hints = part(i / 2)) }
            val graph = LayoutGraph(nodes = nodes, edges = emptyList(), groups = listOf(group))
            val r = run(graph, separate = true, rootPartitioning = true)
            (y(r, "P") < y(r, "Q")) shouldBe true
            (y(r, "Q") < y(r, "X")) shouldBe true
            sameY(r, "A", "B") shouldBe true
            (y(r, "A") < y(r, "C")) shouldBe true
        }

        test("8 crossing edge X to A throws under SEPARATE_CHILDREN") {
            val crossing = wrapGraph.copy(edges = listOf(edge("xa", "X", "A")))
            shouldThrow<org.eclipse.elk.core.UnsupportedGraphException> { run(crossing, separate = true) }
        }
    })
