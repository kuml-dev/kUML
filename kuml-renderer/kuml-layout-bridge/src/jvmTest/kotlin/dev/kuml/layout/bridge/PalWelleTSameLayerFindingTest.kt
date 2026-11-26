@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Layered fake engine: a node sits in layer `max(layerIndex hint, 1 + layer of its predecessors)`, like a real layered engine
 * that cannot put the head of an edge into the layer of its tail.
 */
private class LayeredFakeEngine : KumlLayoutEngine {
    override val id = LayoutEngineId("fake-layered")
    override val capabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds = setOf(DiagramKind.Generic),
            supportedEdgeStyles = setOf(EdgeRouteStyle.Direct),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 100,
            supportsLayerHints = true,
        )

    override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult {
        val layer = HashMap<NodeId, Int>()
        repeat(graph.nodes.size) {
            for (n in graph.nodes) {
                val preds =
                    graph.edges.filter { it.target.nodeId == n.id && it.source.nodeId != n.id }.map {
                        (layer[it.source.nodeId] ?: 0) +
                            1
                    }
                layer[n.id] = maxOf(n.hints.layerIndex ?: 0, preds.maxOrNull() ?: 0)
            }
        }
        val perLayer = HashMap<Int, Int>()
        val nodes =
            graph.nodes.associate { n ->
                val l = layer.getValue(n.id)
                val i = perLayer.merge(l, 1, Int::plus)!! - 1
                n.id to NodeLayout(bounds = Rect(origin = Point(x = i * 250f, y = l * 150f), size = Size(width = 200f, height = 100f)))
            }
        return LayoutResult(
            engineId = id,
            seed = null,
            canvas = Size(width = 1000f, height = 1000f),
            nodes = nodes,
            edges = graph.edges.associate { it.id to EdgeRoute.Direct(source = Point(0f, 0f), target = Point(1f, 1f)) },
            groups = emptyMap(),
        )
    }
}

/** PAL wave T: `KUML-LAYOUT-W014` names why a `sameLayer` group does not share a layer, and never changes the layout. */
class PalWelleTSameLayerFindingTest :
    FunSpec({
        fun graph(
            nodes: List<String>,
            edges: List<Pair<String, String>>,
        ) = LayoutGraph(
            nodes = nodes.map { LayoutNode(id = NodeId(it), intrinsicSize = Size(width = 200f, height = 100f)) },
            edges =
                edges.map { (a, b) ->
                    LayoutEdge(id = EdgeId("conn:p::$a-$b"), source = EndpointRef(NodeId(a)), target = EndpointRef(NodeId(b)))
                },
        )

        fun run(
            g: LayoutGraph,
            spec: LayoutSpec,
            kind: SpecDiagramKind = SpecDiagramKind.SYSML2_IBD,
        ) = LayoutSpecRunner.layout(engine = LayeredFakeEngine(), graph = g, hints = LayoutHints(), spec = spec, kind = kind)

        fun w014(o: SpecLayoutOutcome) = o.findings.filter { it.code == LayoutCodes.SAME_LAYER_NOT_HONOURED }

        test("an edge between two members gives one W014 that names the edge") {
            val out = run(graph(listOf("a", "b"), listOf("a" to "b")), LayoutSpec(sameLayer = listOf(listOf("a", "b"))))
            val f = w014(out)
            f.size shouldBe 1
            f.single().message shouldContain "edge a-b connects a and b and forces different layers"
            f.single().message shouldContain "Remove sameLayer or remove/reverse the edge."
        }

        test("a path through a third node gives W014 with the path text") {
            val out = run(graph(listOf("a", "b", "c"), listOf("a" to "c", "c" to "b")), LayoutSpec(sameLayer = listOf(listOf("a", "b"))))
            val f = w014(out)
            f.size shouldBe 1
            f.single().message shouldContain "lies on a path from a to b"
        }

        test("first(a) with sameLayer(a, b) gives W014 with the pin text") {
            val out =
                run(graph(listOf("a", "b", "c"), listOf("c" to "b")), LayoutSpec(sameLayer = listOf(listOf("a", "b")), first = listOf("a")))
            val f = w014(out)
            f.size shouldBe 1
            f.single().message shouldContain "first(a) moves a to the first layer"
        }

        test("a sameLayer without a conflict gives no W014 and the layout equals the one of an unchecked run") {
            val g = graph(listOf("a", "b", "c"), listOf("a" to "c", "b" to "c"))
            val out = run(g, LayoutSpec(sameLayer = listOf(listOf("a", "b"))))
            w014(out) shouldBe emptyList()
            out.result.nodes
                .getValue(NodeId("a"))
                .bounds.origin.y shouldBe
                out.result.nodes
                    .getValue(NodeId("b"))
                    .bounds.origin.y
        }

        test("the check only reports: the members stay where the engine put them") {
            val out = run(graph(listOf("a", "b"), listOf("a" to "b")), LayoutSpec(sameLayer = listOf(listOf("a", "b"))))
            (w014(out).size == 1) shouldBe true
            val ya =
                out.result.nodes
                    .getValue(NodeId("a"))
                    .bounds.origin.y
            val yb =
                out.result.nodes
                    .getValue(NodeId("b"))
                    .bounds.origin.y
            (yb > ya) shouldBe true
        }

        test("a state machine reports W014 where it used to log a warning") {
            val g = graph(listOf("a", "b", "c"), listOf("a" to "c", "c" to "b"))
            val out = run(g, LayoutSpec(sameLayer = listOf(listOf("a", "b"))), kind = SpecDiagramKind.SYSML2_STM)
            w014(out).size shouldBe 1
        }

        test("an edge between two members of a block definition diagram is drawn straight and is not a W014") {
            val g = graph(listOf("a", "b"), listOf("a" to "b"))
            val out = run(g, LayoutSpec(sameLayer = listOf(listOf("a", "b"))), kind = SpecDiagramKind.SYSML2_BDD)
            w014(out) shouldBe emptyList()
        }
    })
