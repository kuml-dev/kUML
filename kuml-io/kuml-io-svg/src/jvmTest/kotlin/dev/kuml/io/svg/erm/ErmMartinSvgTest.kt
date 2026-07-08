package dev.kuml.io.svg.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Structural + smoke tests for the ERM/Martin (crow's-foot) SVG renderer
 * (V3.4.2). Uses a hand-built [LayoutResult] — this is fine here because
 * these tests exercise the *renderer's* drawing logic, not content-aware
 * sizing (that lives in `ErmContentSizeProviderTest`, which goes through the
 * real `ErmLayoutBridge` → ELK pipeline, per the CLAUDE.md "no hardcoded
 * LayoutResult" pitfall for sizing tests specifically).
 *
 * Each test also writes its SVG (+ auto-generated PNG) to
 * `kuml-io-svg/build/sample-output/erm/<test-name>.svg` for visual review.
 */
class ErmMartinSvgTest :
    StringSpec({

        "entity names render as visible text, no empty canvas" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer, order))
            val diagram = ErmDiagram(name = "Overview")
            val layout =
                layoutOf(
                    "customer" to Rect(Point(20f, 20f), Size(180f, 90f)),
                    "order" to Rect(Point(260f, 20f), Size(180f, 90f)),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "Customer"
            svg shouldContain "Order"
            SampleOutput.write("erm/two-entities.svg", svg)
        }

        "many-cardinality end renders a crow's-foot path" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val rel =
                ErmRelationship(
                    id = "rel1",
                    name = "places",
                    sourceEntityId = "customer",
                    targetEntityId = "order",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview")
            val layout =
                layoutOf(
                    nodes = listOf("customer" to Rect(Point(20f, 20f), Size(180f, 90f)), "order" to Rect(Point(260f, 20f), Size(180f, 90f))),
                    edges = listOf("rel1" to EdgeRoute.Direct(Point(200f, 65f), Point(260f, 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-erm-crowfoot"
            svg shouldContain "kuml-erm-mandatory-marker"
            SampleOutput.write("erm/crowfoot-one-to-many.svg", svg)
        }

        "optional zero-cardinality end renders a circle marker" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val rel =
                ErmRelationship(
                    id = "rel1",
                    name = "places",
                    sourceEntityId = "customer",
                    targetEntityId = "order",
                    sourceCardinality = Cardinality.ZERO_ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview")
            val layout =
                layoutOf(
                    nodes = listOf("customer" to Rect(Point(20f, 20f), Size(180f, 90f)), "order" to Rect(Point(260f, 20f), Size(180f, 90f))),
                    edges = listOf("rel1" to EdgeRoute.Direct(Point(200f, 65f), Point(260f, 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-erm-optional-marker"
            SampleOutput.write("erm/optional-zero-one.svg", svg)
        }

        "weak entity draws a second, inner rect (double border)" {
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val item =
                ErmEntity(
                    id = "item",
                    name = "OrderItem",
                    weak = true,
                    attributes =
                        listOf(
                            ErmAttribute(id = "order_id", name = "order_id", type = ErmDataType.Uuid, foreignKey = ErmForeignKey(targetEntityId = "order")),
                        ),
                )
            val model = ErmModel(name = "Shop", entities = listOf(order, item))
            val diagram = ErmDiagram(name = "Overview")
            val layout =
                layoutOf(
                    "order" to Rect(Point(20f, 20f), Size(180f, 90f)),
                    "item" to Rect(Point(260f, 20f), Size(180f, 90f)),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-erm-entity-inner"
            SampleOutput.write("erm/weak-entity.svg", svg)
        }

        "NON_IDENTIFYING relationship renders dashed, IDENTIFYING renders solid" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val item = ErmEntity(id = "item", name = "OrderItem", weak = true, attributes = listOf(pk("id")))
            val nonIdentifying =
                ErmRelationship(
                    id = "rel1",
                    name = "places",
                    sourceEntityId = "customer",
                    targetEntityId = "order",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    kind = RelationshipKind.NON_IDENTIFYING,
                )
            val identifying =
                ErmRelationship(
                    id = "rel2",
                    name = "contains",
                    sourceEntityId = "order",
                    targetEntityId = "item",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    kind = RelationshipKind.IDENTIFYING,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order, item), relationships = listOf(nonIdentifying, identifying))
            val diagram = ErmDiagram(name = "Overview")
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(Point(20f, 20f), Size(160f, 90f)),
                            "order" to Rect(Point(220f, 20f), Size(160f, 90f)),
                            "item" to Rect(Point(420f, 20f), Size(160f, 90f)),
                        ),
                    edges =
                        listOf(
                            "rel1" to EdgeRoute.Direct(Point(180f, 65f), Point(220f, 65f)),
                            "rel2" to EdgeRoute.Direct(Point(380f, 65f), Point(420f, 65f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-edge-dashed"
            svg shouldContain "kuml-edge\""
            SampleOutput.write("erm/identifying-vs-non-identifying.svg", svg)
        }

        "primary key is underlined, foreign key shows FK marker" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order =
                ErmEntity(
                    id = "order",
                    name = "Order",
                    attributes =
                        listOf(
                            pk("id"),
                            ErmAttribute(
                                id = "customer_id",
                                name = "customer_id",
                                type = ErmDataType.Uuid,
                                foreignKey = ErmForeignKey(targetEntityId = "customer"),
                            ),
                        ),
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order))
            val diagram = ErmDiagram(name = "Overview")
            val layout =
                layoutOf(
                    "customer" to Rect(Point(20f, 20f), Size(180f, 90f)),
                    "order" to Rect(Point(260f, 20f), Size(180f, 120f)),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-erm-pk-underline"
            svg shouldContain "FK"
            svg shouldContain "customer_id : UUID"
            SampleOutput.write("erm/pk-fk-markers.svg", svg)
        }

        "no raw XML entities leak into rendered text" {
            val customer = ErmEntity(id = "customer", name = "Customer's Table", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview")
            val layout = layoutOf("customer" to Rect(Point(20f, 20f), Size(200f, 90f)))

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldNotContain "&amp;apos;"
            svg shouldNotContain "&amp;lt;"
            SampleOutput.write("erm/xml-escape-guard.svg", svg)
        }

        "notation override IDEF1X no longer throws (regression guard, V3.4.5)" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.MARTIN)
            val layout = layoutOf("customer" to Rect(Point(20f, 20f), Size(180f, 90f)))

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), notation = ErmNotation.IDEF1X)

            svg shouldContain "kuml-erm-entity"
        }

        "deterministic output — same input renders byte-identically" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview")
            val layout = layoutOf("customer" to Rect(Point(20f, 20f), Size(180f, 90f)))

            val one = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            one shouldBe two
        }
    })

private fun pk(name: String): ErmAttribute = ErmAttribute(id = name, name = name, type = ErmDataType.Uuid, primaryKey = true)

private fun layoutOf(vararg nodes: Pair<String, Rect>): LayoutResult = layoutOf(nodes.toList(), emptyList())

private fun layoutOf(
    nodes: List<Pair<String, Rect>>,
    edges: List<Pair<String, EdgeRoute>>,
): LayoutResult {
    val maxX = nodes.maxOfOrNull { it.second.origin.x + it.second.size.width } ?: 200f
    val maxY = nodes.maxOfOrNull { it.second.origin.y + it.second.size.height } ?: 150f
    return LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = 1L,
        canvas = Size(maxX + 20f, maxY + 20f),
        nodes = nodes.associate { (id, rect) -> NodeId(id) to NodeLayout(bounds = rect) },
        edges = edges.associate { (id, route) -> EdgeId(id) to route },
        groups = emptyMap(),
    )
}
