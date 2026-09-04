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
         *
         * [sourceNavigable] / [targetNavigable] steuern `UmlAssociationEnd.navigable`
         * an Quelle (Order, `ends[0]`) bzw. Ziel (OrderItem, `ends[1]`) — Default
         * `true`/`true` reproduziert die ursprüngliche Fixture unverändert
         * (fix/uml-association-navigable-arrows).
         */
        fun renderAssociation(
            aggregation: AggregationKind,
            sourceNavigable: Boolean = true,
            targetNavigable: Boolean = true,
        ): String {
            val order = UmlClass(id = "Order", name = "Order")
            val orderItem = UmlClass(id = "OrderItem", name = "OrderItem")
            val assoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", navigable = sourceNavigable),
                            UmlAssociationEnd(
                                typeId = "OrderItem",
                                role = "items",
                                multiplicity = Multiplicity(lower = 1, upper = null),
                                navigable = targetNavigable,
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
                    canvas = Size(width = 400f, height = 360f),
                    nodes =
                        mapOf(
                            NodeId("Order") to
                                NodeLayout(bounds = Rect(origin = Point(x = 120f, y = 40f), size = Size(width = 160f, height = 80f))),
                            NodeId("OrderItem") to
                                NodeLayout(bounds = Rect(origin = Point(x = 120f, y = 240f), size = Size(width = 160f, height = 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc1") to EdgeRoute.Direct(source = Point(x = 200f, y = 120f), target = Point(x = 200f, y = 240f)),
                        ),
                    groups = emptyMap(),
                )
            return KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layout, theme = PlainTheme())
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

        // ── navigable — fix/uml-association-navigable-arrows ────────────────────
        //
        // The unique signature of an OPEN-chevron `<path>` in this fixture (no
        // other element in the diagram draws `fill:none;stroke-linejoin:round`):
        // counting its occurrences distinguishes "no arrowhead" (0) from
        // "exactly one arrowhead" (1) without depending on exact coordinates.

        /** Number of OPEN-chevron `<path>` elements in [svg]. */
        fun openChevronCount(svg: String): Int = Regex("""fill:none;stroke-linejoin:round""").findAll(svg).count()

        test("both ends navigable (default) draws no arrowhead") {
            val svg = renderAssociation(aggregation = AggregationKind.NONE)
            openChevronCount(svg) shouldBe 0
        }

        test("target not navigable (SOURCE_ONLY) draws exactly one arrowhead at the source end") {
            val svg = renderAssociation(aggregation = AggregationKind.NONE, targetNavigable = false)
            openChevronCount(svg) shouldBe 1
            // Tip at the Order/source border — raw layout point (200,120) plus
            // KumlSvgRenderer's default 16px diagram padding (=> 216,136).
            // fmt2 renders whole numbers without a fractional part.
            svg shouldContain """M 221,148 L 216,136 L 211,148"""
        }

        test("source not navigable (TARGET_ONLY) draws exactly one arrowhead at the target end") {
            val svg = renderAssociation(aggregation = AggregationKind.NONE, sourceNavigable = false)
            openChevronCount(svg) shouldBe 1
            // Tip at the OrderItem/target border — raw (200,240) + 16px padding
            // (=> 216,256) — today's Order-Domain behaviour.
            svg shouldContain """M 211,244 L 216,256 L 221,244"""
        }

        test("composite aggregation with target not navigable insets the arrowhead past the diamond") {
            val svg = renderAssociation(aggregation = AggregationKind.COMPOSITE, targetNavigable = false)
            openChevronCount(svg) shouldBe 1
            // Diamond tip still at the node border — raw (200,120) + 16px padding
            // (=> 216,136); chevron tip inset by DIAMOND_LEN (16px) along the
            // edge so the two decorations don't overlap (=> 216,152).
            svg shouldContain """<polygon points="216,136 221,144 216,152 211,144""""
            svg shouldContain """M 221,164 L 216,152 L 211,164"""
        }

        test("neither end navigable draws no arrowhead") {
            val svg = renderAssociation(aggregation = AggregationKind.NONE, sourceNavigable = false, targetNavigable = false)
            openChevronCount(svg) shouldBe 0
        }
    })
