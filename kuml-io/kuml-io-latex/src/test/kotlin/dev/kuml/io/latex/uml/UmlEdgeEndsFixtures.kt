package dev.kuml.io.latex.uml

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlUseCase

/** Fixtures for the line ends of UML edges: diamonds, navigability arrowheads, include/extend, connector. */
internal object UmlEdgeEndsFixtures {
    private fun node(
        x: Float,
        y: Float,
    ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = 140f, height = 60f)))

    private fun assoc(
        id: String,
        from: String,
        to: String,
        aggregation: AggregationKind = AggregationKind.NONE,
        sourceNavigable: Boolean = true,
        targetNavigable: Boolean = true,
        sourceRole: String? = null,
        sourceMultiplicity: Multiplicity = Multiplicity(),
    ) = UmlAssociation(
        id = id,
        aggregation = aggregation,
        ends =
            listOf(
                UmlAssociationEnd(typeId = from, role = sourceRole, multiplicity = sourceMultiplicity, navigable = sourceNavigable),
                UmlAssociationEnd(
                    typeId = to,
                    role = "t$id",
                    multiplicity = Multiplicity(lower = 0, upper = null),
                    navigable = targetNavigable,
                ),
            ),
    )

    private fun layout(
        nodes: Map<NodeId, NodeLayout>,
        edges: Map<EdgeId, EdgeRoute>,
        canvas: Size,
    ) = LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = 1L,
        canvas = canvas,
        nodes = nodes,
        edges = edges,
        groups = emptyMap(),
    )

    private fun direct(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) = EdgeRoute.Direct(source = Point(x = x1, y = y1), target = Point(x = x2, y = y2))

    /** Class diagram: six edges, one per diamond and navigability combination plus a source role/multiplicity. */
    fun classDiagramAndLayout(): Pair<KumlDiagram, LayoutResult> {
        val ids = ('A'..'L').map { it.toString() }
        val classes: List<UmlElement> = ids.map { UmlClass(id = it, name = "Class$it") }
        val rels =
            listOf(
                assoc(id = "comp", from = "A", to = "B", aggregation = AggregationKind.COMPOSITE),
                assoc(
                    id = "aggT",
                    from = "C",
                    to = "D",
                    aggregation = AggregationKind.SHARED,
                    targetNavigable = true,
                    sourceNavigable = false,
                ),
                assoc(
                    id = "compS",
                    from = "E",
                    to = "F",
                    aggregation = AggregationKind.COMPOSITE,
                    sourceNavigable = true,
                    targetNavigable = false,
                ),
                assoc(id = "nav", from = "G", to = "H", sourceNavigable = false, targetNavigable = true),
                assoc(id = "std", from = "I", to = "J"),
                assoc(
                    id = "srcLabels",
                    from = "K",
                    to = "L",
                    aggregation = AggregationKind.COMPOSITE,
                    sourceRole = "owner",
                    sourceMultiplicity = Multiplicity(lower = 0, upper = null),
                ),
            )
        val nodes = linkedMapOf<NodeId, NodeLayout>()
        val edges = linkedMapOf<EdgeId, EdgeRoute>()
        rels.forEachIndexed { i, r ->
            val y = 20f + i * 220f
            nodes[NodeId(ids[2 * i])] = node(x = 20f, y = y)
            nodes[NodeId(ids[2 * i + 1])] = node(x = 20f, y = y + 140f)
            edges[EdgeId(r.id)] = direct(x1 = 90f, y1 = y + 60f, x2 = 90f, y2 = y + 140f)
        }
        return KumlDiagram(name = "UmlEdgeEnds", elements = classes + rels) to
            layout(nodes = nodes, edges = edges, canvas = Size(width = 200f, height = 1340f))
    }

    /** Use-case-style diagram: include, extend and a connector. */
    fun useCaseDiagramAndLayout(): Pair<KumlDiagram, LayoutResult> {
        val elements: List<UmlElement> =
            listOf(
                UmlUseCase(id = "U1", name = "Order"),
                UmlUseCase(id = "U2", name = "Pay"),
                UmlUseCase(id = "U3", name = "Discount"),
                UmlUseCase(id = "U4", name = "Ship"),
                UmlInclude(id = "inc", baseId = "U1", additionId = "U2"),
                UmlExtend(id = "ext", baseId = "U1", extensionId = "U3"),
                UmlConnector(id = "con", end1Id = "U1", end2Id = "U4"),
            )
        val nodes =
            linkedMapOf(
                NodeId("U1") to node(x = 180f, y = 20f),
                NodeId("U2") to node(x = 20f, y = 200f),
                NodeId("U3") to node(x = 180f, y = 200f),
                NodeId("U4") to node(x = 340f, y = 200f),
            )
        val edges =
            linkedMapOf<EdgeId, EdgeRoute>(
                EdgeId("inc") to direct(x1 = 210f, y1 = 80f, x2 = 90f, y2 = 200f),
                EdgeId("ext") to direct(x1 = 250f, y1 = 80f, x2 = 250f, y2 = 200f),
                EdgeId("con") to direct(x1 = 290f, y1 = 80f, x2 = 410f, y2 = 200f),
            )
        return KumlDiagram(name = "UmlUseCaseEnds", elements = elements) to
            layout(nodes = nodes, edges = edges, canvas = Size(width = 500f, height = 280f))
    }

    fun renderClass(): String = render(classDiagramAndLayout())

    fun renderUseCase(): String = render(useCaseDiagramAndLayout())

    private fun render(p: Pair<KumlDiagram, LayoutResult>): String =
        KumlLatexRenderer.toLatex(diagram = p.first, layoutResult = p.second, options = LatexRenderOptions(standalone = true))
}
