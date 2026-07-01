package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Regressionsschutz für zwei Renderer-Lücken (V0.17), aufgefallen am
 * Order-Domain-Beispiel
 * [[03 Bereiche/kUML/Beispiele/01 UML Klasse – Order Domain]]:
 *
 * 1. Rollennamen an Assoziationsenden (`role = "items"`) wurden nie gezeichnet.
 * 2. Aggregations-/Kompositionsrauten (`AggregationKind.COMPOSITE`) fehlten —
 *    [renderUmlAssociation] zeichnete immer nur den offenen Pfeil.
 */
class UmlAssociationDecorationSvgTest :
    FunSpec({

        /**
         * Baut ein minimales Klassendiagramm `Order → OrderItem` mit gegebener
         * [aggregation] und festem Rollennamen `items` + Multiplizität `1..*`
         * am Zielende. Liefert das gerenderte SVG.
         */
        fun renderAssociation(aggregation: AggregationKind): String {
            val order = UmlClass(id = "Order", name = "Order")
            val orderItem = UmlClass(id = "OrderItem", name = "OrderItem")
            val assoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order"),
                            UmlAssociationEnd(
                                typeId = "OrderItem",
                                role = "items",
                                multiplicity = Multiplicity(lower = 1, upper = null),
                            ),
                        ),
                    aggregation = aggregation,
                )
            val diagram =
                KumlDiagram(
                    name = "T",
                    type = DiagramType.CLASS,
                    elements = listOf(order, orderItem, assoc),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(400f, 360f),
                    nodes =
                        mapOf(
                            NodeId("Order") to NodeLayout(bounds = Rect(Point(120f, 40f), Size(160f, 80f))),
                            NodeId("OrderItem") to NodeLayout(bounds = Rect(Point(120f, 240f), Size(160f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc1") to EdgeRoute.Direct(source = Point(200f, 120f), target = Point(200f, 240f)),
                        ),
                    groups = emptyMap(),
                )
            return KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
        }

        /** Extracts the `fill:` and `stroke:` colours of the first `<polygon>` style. */
        fun polygonFillAndStroke(svg: String): Pair<String, String>? {
            val style = Regex("""<polygon[^>]*style="([^"]*)"""").find(svg)?.groupValues?.get(1) ?: return null
            val fill =
                Regex("""fill:([^;]+)""")
                    .find(style)
                    ?.groupValues
                    ?.get(1)
                    ?.trim() ?: return null
            val stroke =
                Regex("""stroke:([^;]+)""")
                    .find(style)
                    ?.groupValues
                    ?.get(1)
                    ?.trim() ?: return null
            return fill to stroke
        }

        test("composition renders a FILLED diamond plus the role name and multiplicity") {
            val svg = renderAssociation(AggregationKind.COMPOSITE)

            // Both end labels are now drawn (the role was previously dropped).
            svg shouldContain "items"
            svg shouldContain "1..*"

            // The composition diamond is a 4-point polygon; a plain association
            // draws none. Filled ⇒ fill colour equals stroke colour.
            val (fill, stroke) = requireNotNull(polygonFillAndStroke(svg)) { "Expected a diamond <polygon>" }
            (fill == stroke) shouldBe true
        }

        test("shared aggregation renders a HOLLOW diamond (fill differs from stroke)") {
            val svg = renderAssociation(AggregationKind.SHARED)

            val (fill, stroke) = requireNotNull(polygonFillAndStroke(svg)) { "Expected a diamond <polygon>" }
            (fill == stroke) shouldBe false
        }

        test("plain association draws the role name but no aggregation diamond") {
            val svg = renderAssociation(AggregationKind.NONE)

            svg shouldContain "items"
            svg shouldContain "1..*"
            // No diamond → no polygon on a NONE association.
            svg shouldNotContain "<polygon"
        }
    })
