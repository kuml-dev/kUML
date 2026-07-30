package dev.kuml.io.svg.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.io.svg.SvgRenderOptions
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
 * Structural + smoke tests for the ERM/Bachman SVG renderer (V3.4.3). Mirrors
 * `ErmMartinSvgTest`'s structure — see that file's KDoc for why a hand-built
 * [LayoutResult] is fine here (renderer drawing logic, not content-aware
 * sizing).
 *
 * Each test also writes its SVG (+ auto-generated PNG) to
 * `kuml-io-svg/build/sample-output/erm/bachman-<test-name>.svg` for visual
 * review.
 */
class ErmBachmanSvgTest :
    StringSpec({

        "many-cardinality end renders a Bachman arrowhead" {
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
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 90f)),
                            "order" to Rect(origin = Point(x = 260f, y = 20f), size = Size(width = 180f, height = 90f)),
                        ),
                    edges = listOf("rel1" to EdgeRoute.Direct(source = Point(x = 200f, y = 65f), target = Point(x = 260f, y = 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "kuml-erm-bachman-arrow"
            svg shouldContain "kuml-erm-bachman-mandatory"
            SampleOutput.write(filename = "erm/bachman-one-to-many.svg", content = svg)
        }

        "mandatory (min>=1) end renders a filled circle" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val rel =
                ErmRelationship(
                    id = "rel1",
                    name = "places",
                    sourceEntityId = "customer",
                    targetEntityId = "order",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ONE_MANY,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 90f)),
                            "order" to Rect(origin = Point(x = 260f, y = 20f), size = Size(width = 180f, height = 90f)),
                        ),
                    edges = listOf("rel1" to EdgeRoute.Direct(source = Point(x = 200f, y = 65f), target = Point(x = 260f, y = 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "kuml-erm-bachman-mandatory"
            SampleOutput.write(filename = "erm/bachman-mandatory-one-to-many.svg", content = svg)
        }

        "optional (min=0) end renders a hollow circle" {
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
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 90f)),
                            "order" to Rect(origin = Point(x = 260f, y = 20f), size = Size(width = 180f, height = 90f)),
                        ),
                    edges = listOf("rel1" to EdgeRoute.Direct(source = Point(x = 200f, y = 65f), target = Point(x = 260f, y = 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "kuml-erm-optional-marker"
            SampleOutput.write(filename = "erm/bachman-optional-zero-one.svg", content = svg)
        }

        "entity boxes render identically to Martin (shared renderer)" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer, order))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout =
                layoutOf(
                    "customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 90f)),
                    "order" to Rect(origin = Point(x = 260f, y = 20f), size = Size(width = 180f, height = 90f)),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "kuml-erm-entity"
            svg shouldContain "Customer"
            svg shouldContain "Order"
            SampleOutput.write(filename = "erm/bachman-two-entities.svg", content = svg)
        }

        "NON_IDENTIFYING dashed / IDENTIFYING solid" {
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
            val model =
                ErmModel(name = "Shop", entities = listOf(customer, order, item), relationships = listOf(nonIdentifying, identifying))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 160f, height = 90f)),
                            "order" to Rect(origin = Point(x = 220f, y = 20f), size = Size(width = 160f, height = 90f)),
                            "item" to Rect(origin = Point(x = 420f, y = 20f), size = Size(width = 160f, height = 90f)),
                        ),
                    edges =
                        listOf(
                            "rel1" to EdgeRoute.Direct(source = Point(x = 180f, y = 65f), target = Point(x = 220f, y = 65f)),
                            "rel2" to EdgeRoute.Direct(source = Point(x = 380f, y = 65f), target = Point(x = 420f, y = 65f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "kuml-edge-dashed"
            svg shouldContain "kuml-edge\""
            SampleOutput.write(filename = "erm/bachman-identifying-vs-non-identifying.svg", content = svg)
        }

        "no raw XML entities leak into rendered text" {
            val customer = ErmEntity(id = "customer", name = "Customer's Table", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout = layoutOf("customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 200f, height = 90f)))

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            svg shouldNotContain "&amp;apos;"
            svg shouldNotContain "&amp;lt;"
            SampleOutput.write(filename = "erm/bachman-xml-escape-guard.svg", content = svg)
        }

        "deterministic output — same input renders byte-identically" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout = layoutOf("customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 90f)))

            val one = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())
            val two = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())
            one shouldBe two
        }

        "BACHMAN no longer throws (regression guard)" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.MARTIN)
            val layout = layoutOf("customer" to Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 90f)))

            val svg =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = diagram,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    notation = ErmNotation.BACHMAN,
                )

            svg shouldContain "kuml-erm-entity"
        }

        // ── Self-loop edge-label-collision regression guard (fix/erm-martin-edge-label-collision) ──

        "self-referential relationship name label does not overflow into the entity box" {
            val category = ErmEntity(id = "category", name = "Category", attributes = listOf(pk("id")))
            val rel =
                ErmRelationship(
                    id = "rel1",
                    name = "subcategory of",
                    sourceEntityId = "category",
                    targetEntityId = "category",
                    sourceCardinality = Cardinality.ZERO_ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    sourceRole = "parent",
                    targetRole = "child",
                )
            val model = ErmModel(name = "Catalog", entities = listOf(category), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.BACHMAN)
            val layout =
                layoutOf(
                    nodes = listOf("category" to Rect(origin = Point(x = 200f, y = 100f), size = Size(width = 180f, height = 120f))),
                    edges =
                        listOf(
                            "rel1" to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(x = 200f, y = 140f),
                                    target = Point(x = 200f, y = 190f),
                                    waypoints = listOf(Point(x = 180f, y = 140f), Point(x = 180f, y = 190f)),
                                    cornerRadiusPx = 6f,
                                ),
                        ),
                )

            val svg =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = diagram,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    options = SvgRenderOptions(prettyPrint = false),
                )

            val nameLabel = edgeLabels(svg).single { it.text == "subcategory of" }
            // V3.4.x — see ErmMartinSvgTest's matching test for the rationale:
            // ERM self-loops now route through SelfLoopRouter, which bulges
            // outward from the node's RIGHT edge (x=380 = origin.x=200 + width=180).
            nameLabel.textAnchor shouldBe "start"
            (nameLabel.x >= 380f) shouldBe true
            SampleOutput.write(filename = "erm/bachman-self-loop-name-label-no-overflow.svg", content = svg)
        }
    })

private fun pk(name: String): ErmAttribute = ErmAttribute(id = name, name = name, type = ErmDataType.Uuid, primaryKey = true)

private fun layoutOf(vararg nodes: Pair<String, Rect>): LayoutResult = layoutOf(nodes = nodes.toList(), edges = emptyList())

private fun layoutOf(
    nodes: List<Pair<String, Rect>>,
    edges: List<Pair<String, EdgeRoute>>,
): LayoutResult {
    val maxX = nodes.maxOfOrNull { it.second.origin.x + it.second.size.width } ?: 200f
    val maxY = nodes.maxOfOrNull { it.second.origin.y + it.second.size.height } ?: 150f
    return LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = 1L,
        canvas = Size(width = maxX + 20f, height = maxY + 20f),
        nodes = nodes.associate { (id, rect) -> NodeId(id) to NodeLayout(bounds = rect) },
        edges = edges.associate { (id, route) -> EdgeId(id) to route },
        groups = emptyMap(),
    )
}
