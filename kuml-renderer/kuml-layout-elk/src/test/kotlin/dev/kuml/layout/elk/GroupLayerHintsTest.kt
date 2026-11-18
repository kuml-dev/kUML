package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayerPin
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** Nacharbeit 107: the engine honours member layer hints in a flagged, self-contained compound group. */
class GroupLayerHintsTest :
    FunSpec({
        val tol = 0.5f
        val g = GroupId("G")
        val padding = Insets(top = 36f, right = 20f, bottom = 20f, left = 20f)

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

        fun graph(
            nodes: List<LayoutNode>,
            edges: List<LayoutEdge> = emptyList(),
            flagged: Boolean = true,
            minSize: Size? = null,
            nestedGroup: Boolean = false,
        ) = LayoutGraph(
            nodes = nodes,
            edges = edges,
            groups =
                listOf(LayoutGroup(id = g, padding = padding, layoutAsCompound = true, memberLayerHints = flagged, minSize = minSize)) +
                    if (nestedGroup) {
                        listOf(LayoutGroup(id = GroupId("H"), parent = g, padding = padding, layoutAsCompound = true))
                    } else {
                        emptyList()
                    },
        )

        fun layout(graph: LayoutGraph): LayoutResult = ElkLayoutEngine().layout(graph = graph, hints = LayoutHints())

        fun y(
            r: LayoutResult,
            id: String,
        ) = r.nodes
            .getValue(NodeId(id))
            .bounds.origin.y

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
        ) = inside(r, p) &&
            (
                abs(p.x - r.origin.x) < tol ||
                    abs(p.x - r.origin.x - r.size.width) < tol ||
                    abs(p.y - r.origin.y) < tol ||
                    abs(p.y - r.origin.y - r.size.height) < tol
            )

        fun warnings(r: LayoutResult) = r.warnings.filter { it.code == "hint.ignored.layerInCompound" }

        val ids = listOf("A", "B", "C", "D", "E", "F")

        fun wrapNodes(withHints: Boolean = true) =
            ids.mapIndexed { i, id -> node(id, hints = if (withHints) NodeHints(layerIndex = i / 2) else NodeHints()) } +
                node("X", inGroup = false)

        fun assertInside(
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
                val pts = points(r.edges.getValue(e.id))
                val s = r.nodes[e.source.nodeId]?.bounds ?: r.groups.getValue(GroupId(e.source.nodeId.value)).bounds
                val t = r.nodes[e.target.nodeId]?.bounds ?: r.groups.getValue(GroupId(e.target.nodeId.value)).bounds
                onBorder(s, pts.first()) shouldBe true
                onBorder(t, pts.last()) shouldBe true
            }
        }

        test("wrap: rows of at most two, rows ascending, no warning, everything inside the frame") {
            val gr = graph(wrapNodes())
            val r = layout(gr)
            val rows = ids.groupBy { Math.round(y(r, it) / 4f) }
            rows.values.maxOf { it.size } shouldBeLessThanOrEqualTo 2
            rows.size shouldBe 3
            warnings(r).shouldBeEmpty()
            assertInside(r, gr)
        }

        test("sameLayer: members of one partition share a row") {
            val nodes =
                listOf(
                    node("A", hints = NodeHints(layerIndex = 0)),
                    node("B", hints = NodeHints(layerIndex = 1)),
                    node("C", hints = NodeHints(layerIndex = 0)),
                )
            val gr = graph(nodes + node("X", inGroup = false), listOf(edge("ab", "A", "B"), edge("bc", "B", "C")))
            val r = layout(gr)
            (abs(y(r, "A") - y(r, "C")) < tol) shouldBe true
            assertInside(r, gr)
        }

        test("first and last land on the extreme rows") {
            val nodes =
                listOf(
                    node("A"),
                    node("B"),
                    node("C"),
                    node("D", hints = NodeHints(layerPin = LayerPin.First)),
                    node("E", hints = NodeHints(layerPin = LayerPin.Last)),
                )
            val gr = graph(nodes + node("X", inGroup = false), listOf(edge("ab", "A", "B"), edge("bc", "B", "C")))
            val r = layout(gr)
            for (id in listOf("A", "B", "C", "E")) (y(r, "D") <= y(r, id) + tol) shouldBe true
            for (id in listOf("A", "B", "C", "D")) (y(r, "E") >= y(r, id) - tol) shouldBe true
            (y(r, "E") > y(r, "A")) shouldBe true
            warnings(r).shouldBeEmpty()
            assertInside(r, gr)
        }

        test("a crossing edge: result equals the unflagged one and the warning stays") {
            val edges = listOf(edge("xa", "X", "A"))
            val flagged = layout(graph(wrapNodes(), edges, flagged = true))
            val plain = layout(graph(wrapNodes(), edges, flagged = false))
            flagged.nodes shouldBe plain.nodes
            flagged.edges shouldBe plain.edges
            warnings(flagged) shouldHaveSize 6
        }

        test("flagged without any hint: result equals the unflagged one") {
            val flagged = layout(graph(wrapNodes(withHints = false), flagged = true))
            val plain = layout(graph(wrapNodes(withHints = false), flagged = false))
            flagged.nodes shouldBe plain.nodes
            flagged.groups shouldBe plain.groups
        }

        test("a group with a nested group is still ignored") {
            val flagged = layout(graph(wrapNodes(), flagged = true, nestedGroup = true))
            warnings(flagged) shouldHaveSize 6
            val plain = layout(graph(wrapNodes(), flagged = false, nestedGroup = true))
            flagged.nodes shouldBe plain.nodes
        }

        test("an edge from an outside node to the group anchor works") {
            val gr = graph(wrapNodes(), listOf(edge("xg", "X", "G")))
            val r = layout(gr)
            r.edges.containsKey(EdgeId("xg")) shouldBe true
            ids.groupBy { Math.round(y(r, it) / 4f) }.values.maxOf { it.size } shouldBeLessThanOrEqualTo 2
        }

        test("minSize is still met") {
            val r = layout(graph(wrapNodes(), minSize = Size(width = 600f, height = 500f)))
            val gb = r.groups.getValue(g).bounds
            (gb.size.width >= 600f - tol) shouldBe true
            (gb.size.height >= 500f - tol) shouldBe true
        }

        test("deterministic over two runs") {
            val a = layout(graph(wrapNodes()))
            val b = layout(graph(wrapNodes()))
            a.nodes shouldBe b.nodes
            a.edges shouldBe b.edges
        }
    })
