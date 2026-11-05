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
import dev.kuml.uml.UmlDependency

/** Four classes with a named association (roles, multiplicities), an aggregation and a `use` dependency. */
internal object UmlEdgeLabelFixtures {
    private fun node(
        x: Float,
        y: Float,
    ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = 140f, height = 60f)))

    fun diagramAndLayout(): Pair<KumlDiagram, LayoutResult> {
        val classes =
            listOf("A" to "Customer", "B" to "Order", "C" to "Address", "D" to "Invoice")
                .map { (id, n) -> UmlClass(id = id, name = n) }
        val places =
            UmlAssociation(
                id = "places",
                name = "places",
                stereotypes = listOf("entity"),
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "A", role = "buyer", multiplicity = Multiplicity(lower = 1, upper = 1)),
                        UmlAssociationEnd(typeId = "B", role = "order", multiplicity = Multiplicity(lower = 0, upper = null)),
                    ),
            )
        val lives =
            UmlAssociation(
                id = "lives",
                aggregation = AggregationKind.SHARED,
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "A", multiplicity = Multiplicity(lower = 1, upper = 1)),
                        UmlAssociationEnd(typeId = "C", role = "home", multiplicity = Multiplicity(lower = 0, upper = 1)),
                    ),
            )
        val uses = UmlDependency(id = "uses", clientId = "B", supplierId = "D", name = "<<use>>")
        val nodes =
            linkedMapOf(
                NodeId("A") to node(x = 20f, y = 20f),
                NodeId("B") to node(x = 260f, y = 20f),
                NodeId("C") to node(x = 20f, y = 200f),
                NodeId("D") to node(x = 260f, y = 200f),
            )
        val edges =
            linkedMapOf<EdgeId, EdgeRoute>(
                EdgeId("places") to EdgeRoute.Direct(source = Point(x = 160f, y = 50f), target = Point(x = 260f, y = 50f)),
                EdgeId("lives") to EdgeRoute.Direct(source = Point(x = 90f, y = 80f), target = Point(x = 90f, y = 200f)),
                EdgeId("uses") to
                    EdgeRoute.OrthogonalRounded(
                        source = Point(x = 330f, y = 80f),
                        target = Point(x = 330f, y = 200f),
                        waypoints = listOf(Point(x = 330f, y = 140f)),
                        cornerRadiusPx = 4f,
                    ),
            )
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 420f, height = 280f),
                nodes = nodes,
                edges = edges,
                groups = emptyMap(),
            )
        return KumlDiagram(name = "UmlEdgeLabels", elements = classes + listOf(places, lives, uses)) to layout
    }

    fun render(standalone: Boolean = true): String {
        val (d, l) = diagramAndLayout()
        return KumlLatexRenderer.toLatex(diagram = d, layoutResult = l, options = LatexRenderOptions(standalone = standalone))
    }
}
