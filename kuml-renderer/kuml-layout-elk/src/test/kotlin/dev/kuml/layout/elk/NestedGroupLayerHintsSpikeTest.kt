package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
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
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.util.BasicProgressMonitor

/**
 * Nacharbeit 119 spike: does ELK honour partitions of a compound that lays out its children separately when that
 * compound contains nested compounds? Drives the ELK graph directly and pins the observed behaviour; the findings
 * justify the subtree rule in `blockingEdgeInSubtree` and the forcing of `SEPARATE_CHILDREN` on descendants.
 */
class NestedGroupLayerHintsSpikeTest :
    FunSpec({
        val tol = 0.5f

        fun group(
            id: String,
            parent: String? = null,
        ) = LayoutGroup(
            id = GroupId(id),
            padding = Insets(top = 36f, right = 20f, bottom = 20f, left = 20f),
            layoutAsCompound = true,
            parent = parent?.let { GroupId(it) },
        )

        fun node(
            id: String,
            inGroup: String?,
            partition: Int? = null,
        ) = LayoutNode(
            id = NodeId(id),
            intrinsicSize = Size(width = 80f, height = 40f),
            groupId = inGroup?.let { GroupId(it) },
            hints = NodeHints(layerIndex = partition),
        )

        fun edge(
            id: String,
            s: String,
            t: String,
        ) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(s)), target = EndpointRef(nodeId = NodeId(t)))

        /** [separate] groups get SEPARATE_CHILDREN; those also in [partitioned] additionally get partitioning. */
        fun run(
            graph: LayoutGraph,
            separate: Set<String>,
            partitioned: Set<String> = separate,
        ): LayoutResult {
            val builder = ElkGraphBuilder(graph)
            val root = builder.build()
            val hints = LayoutHints(direction = LayoutDirection.TopToBottom)
            HintsMapper.applyGlobalHints(root = root, hints = hints, config = ElkEngineConfiguration.DEFAULT)
            HintsMapper.applyGroupPadding(builder = builder, hints = hints, config = ElkEngineConfiguration.DEFAULT)
            for (id in separate) {
                val elk = builder.groupMap.getValue(GroupId(id))
                elk.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
                elk.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
                if (id in partitioned) {
                    elk.setProperty(CoreOptions.PARTITIONING_ACTIVATE, true)
                    elk.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.SIMPLE)
                    elk.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, false)
                }
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

        fun y(
            r: LayoutResult,
            id: String,
        ) = r.nodes
            .getValue(NodeId(id))
            .bounds.origin.y

        fun insideGroup(
            r: LayoutResult,
            outer: String,
            inner: String,
        ): Boolean {
            val o = r.groups.getValue(GroupId(outer)).bounds
            val i = r.groups.getValue(GroupId(inner)).bounds
            return i.origin.x >= o.origin.x - tol &&
                i.origin.y >= o.origin.y - tol &&
                i.origin.x + i.size.width <= o.origin.x + o.size.width + tol &&
                i.origin.y + i.size.height <= o.origin.y + o.size.height + tol
        }

        /** C holds direct leaves P0..P2 (partitions 0..2, declared in reverse) and nested compound N with n1 -> n2. */
        fun baseGraph(
            nested: List<LayoutNode> = listOf(node("n1", "N"), node("n2", "N")),
            extraEdges: List<LayoutEdge> = listOf(edge("n12", "n1", "n2")),
        ) = LayoutGraph(
            nodes = listOf(node("P2", "C", 2), node("P1", "C", 1), node("P0", "C", 0)) + nested,
            edges = extraEdges,
            groups = listOf(group("C"), group("N", parent = "C")),
        )

        test("a nested SEPARATE group without partitions: direct partitions honoured, N inside C") {
            val r = run(baseGraph(), separate = setOf("C", "N"), partitioned = setOf("C"))
            (y(r, "P0") < y(r, "P1")) shouldBe true
            (y(r, "P1") < y(r, "P2")) shouldBe true
            insideGroup(r, "C", "N") shouldBe true
        }

        test("b nested group left INCLUDE_CHILDREN: observed behaviour pinned") {
            val r = run(baseGraph(), separate = setOf("C"))
            // Pinned finding: ELK still places C's direct leaves by partition when N lays out jointly with C.
            // Forcing SEPARATE_CHILDREN on descendants is kept anyway (deterministic, same as the flat case).
            (y(r, "P0") <= y(r, "P1")) shouldBe true
            (y(r, "P1") <= y(r, "P2")) shouldBe true
            insideGroup(r, "C", "N") shouldBe true
        }

        test("c edge from a node in N to a direct leaf of C: observed behaviour pinned") {
            val graph = baseGraph(extraEdges = listOf(edge("nx", "n1", "P0")))
            val r = run(graph, separate = setOf("C", "N"), partitioned = setOf("C"))
            val route = r.edges.getValue(EdgeId("nx"))
            val start =
                when (route) {
                    is dev.kuml.layout.EdgeRoute.Direct -> route.source
                    is dev.kuml.layout.EdgeRoute.OrthogonalRounded -> route.source
                    is dev.kuml.layout.EdgeRoute.TreeRounded -> route.source
                    is dev.kuml.layout.EdgeRoute.Bezier -> route.source
                }
            // Pinned finding: ELK does not throw but produces a degenerate route that does not touch n1.
            val n1 = r.nodes.getValue(NodeId("n1")).bounds
            val touches =
                start.x >= n1.origin.x - tol &&
                    start.x <= n1.origin.x + n1.size.width + tol &&
                    start.y >= n1.origin.y - tol &&
                    start.y <= n1.origin.y + n1.size.height + tol
            touches shouldBe false
        }

        test("d nested SEPARATE group with its own partitions: both levels honoured") {
            val graph =
                baseGraph(nested = listOf(node("m2", "N", 1), node("m1", "N", 0)), extraEdges = emptyList())
            val r = run(graph, separate = setOf("C", "N"))
            (y(r, "P0") < y(r, "P1")) shouldBe true
            (y(r, "P1") < y(r, "P2")) shouldBe true
            (y(r, "m1") < y(r, "m2")) shouldBe true
        }

        test("e hinted child under a parent without hints (parent stays INCLUDE_CHILDREN)") {
            val graph =
                LayoutGraph(
                    nodes = listOf(node("d2", "D", 1), node("d1", "D", 0), node("o", "G")),
                    edges = emptyList(),
                    groups = listOf(group("G"), group("D", parent = "G")),
                )
            val r = run(graph, separate = setOf("D"))
            (y(r, "d1") < y(r, "d2")) shouldBe true
            insideGroup(r, "G", "D") shouldBe true
        }

        test("f three levels all SEPARATE, hints at the outer and the innermost level") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(node("c2", "C", 1), node("c1", "C", 0)) +
                            listOf(node("x", "N")) +
                            listOf(node("m2", "M", 1), node("m1", "M", 0)),
                    edges = emptyList(),
                    groups = listOf(group("C"), group("N", parent = "C"), group("M", parent = "N")),
                )
            val r = run(graph, separate = setOf("C", "N", "M"), partitioned = setOf("C", "M"))
            (y(r, "c1") < y(r, "c2")) shouldBe true
            (y(r, "m1") < y(r, "m2")) shouldBe true
            insideGroup(r, "C", "N") shouldBe true
            insideGroup(r, "N", "M") shouldBe true
        }

        test("g edge between a direct leaf of C and the nested compound N itself does not throw") {
            val graph = baseGraph(extraEdges = listOf(edge("pn", "P0", "N")))
            val r = run(graph, separate = setOf("C", "N"), partitioned = setOf("C"))
            insideGroup(r, "C", "N") shouldBe true
        }
    })
