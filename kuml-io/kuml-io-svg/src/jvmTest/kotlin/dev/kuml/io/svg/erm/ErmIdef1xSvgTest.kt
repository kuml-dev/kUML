package dev.kuml.io.svg.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCategory
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
import dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Structural + smoke tests for the ERM/IDEF1X SVG renderer (V3.4.5). Mirrors
 * `ErmBachmanSvgTest`'s structure — a hand-built [LayoutResult] is fine here
 * (renderer drawing logic, not content-aware sizing or real ELK routing,
 * which is covered by `ErmIdef1xLayoutBridgeTest`).
 *
 * Each test also writes its SVG (+ auto-generated PNG) to
 * `kuml-io-svg/build/sample-output/erm/idef1x-<test-name>.svg` for visual
 * review.
 */
class ErmIdef1xSvgTest :
    StringSpec({

        "IDENTIFYING relationship renders a solid line and a child-end dot" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val item = ErmEntity(id = "item", name = "OrderItem", weak = true, attributes = listOf(pk("id")))
            val rel =
                ErmRelationship(
                    id = "rel1",
                    name = "contains",
                    sourceEntityId = "customer",
                    targetEntityId = "item",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    kind = RelationshipKind.IDENTIFYING,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, item), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout =
                layoutOf(
                    nodes = listOf("customer" to Rect(Point(20f, 20f), Size(180f, 90f)), "item" to Rect(Point(260f, 20f), Size(180f, 90f))),
                    edges = listOf("rel1" to EdgeRoute.Direct(Point(200f, 65f), Point(260f, 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-edge\""
            svg shouldContain "kuml-erm-idef1x-dot"
            SampleOutput.write("erm/idef1x-identifying.svg", svg)
        }

        "NON_IDENTIFYING relationship renders a dashed line; optional parent gets a diamond" {
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
                    kind = RelationshipKind.NON_IDENTIFYING,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(Point(20f, 20f), Size(180f, 90f)),
                            "order" to Rect(Point(260f, 20f), Size(180f, 90f)),
                        ),
                    edges = listOf("rel1" to EdgeRoute.Direct(Point(200f, 65f), Point(260f, 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-edge-dashed"
            svg shouldContain "kuml-erm-idef1x-diamond"
            SampleOutput.write("erm/idef1x-non-identifying-optional-parent.svg", svg)
        }

        "mandatory parent (min>=1) end draws no diamond" {
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
                    kind = RelationshipKind.NON_IDENTIFYING,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(Point(20f, 20f), Size(180f, 90f)),
                            "order" to Rect(Point(260f, 20f), Size(180f, 90f)),
                        ),
                    edges = listOf("rel1" to EdgeRoute.Direct(Point(200f, 65f), Point(260f, 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldNotContain "class=\"kuml-erm-idef1x-diamond\""
            SampleOutput.write("erm/idef1x-mandatory-parent-no-diamond.svg", svg)
        }

        "cardinality annotation P/Z render for (1,N) and (0,1) child ends" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val profile = ErmEntity(id = "profile", name = "Profile", attributes = listOf(pk("id")))
            val oneOrMore =
                ErmRelationship(
                    id = "rel1",
                    name = "places",
                    sourceEntityId = "customer",
                    targetEntityId = "order",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ONE_MANY,
                )
            val zeroOrOne =
                ErmRelationship(
                    id = "rel2",
                    name = "has",
                    sourceEntityId = "customer",
                    targetEntityId = "profile",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_ONE,
                )
            val model =
                ErmModel(
                    name = "Shop",
                    entities = listOf(customer, order, profile),
                    relationships = listOf(oneOrMore, zeroOrOne),
                )
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(Point(20f, 20f), Size(160f, 90f)),
                            "order" to Rect(Point(220f, 20f), Size(160f, 90f)),
                            "profile" to Rect(Point(420f, 20f), Size(160f, 90f)),
                        ),
                    edges =
                        listOf(
                            "rel1" to EdgeRoute.Direct(Point(180f, 65f), Point(220f, 65f)),
                            "rel2" to EdgeRoute.Direct(Point(180f, 65f), Point(420f, 65f)),
                        ),
                )

            // Non-pretty output so the cardinality glyph's text content sits
            // directly between its tag brackets (`>P<`) for a reliable substring check.
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), SvgRenderOptions(prettyPrint = false))

            svg shouldContain "kuml-erm-idef1x-card"
            svg shouldContain ">P<"
            svg shouldContain ">Z<"
            SampleOutput.write("erm/idef1x-cardinality-annotations.svg", svg)
        }

        "dependent entity (weak) renders with rounded corners; independent entity does not" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val item = ErmEntity(id = "item", name = "OrderItem", weak = true, attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer, item))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout =
                layoutOf(
                    "customer" to Rect(Point(20f, 20f), Size(180f, 90f)),
                    "item" to Rect(Point(260f, 20f), Size(180f, 90f)),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), SvgRenderOptions(prettyPrint = false))

            // The weak "item" entity's outer rect carries rx/ry; the independent
            // "customer" entity's does not.
            svg shouldContain "id=\"item\""
            val itemGroupStart = svg.indexOf("id=\"item\"")
            val itemRectSnippet = svg.substring(itemGroupStart, itemGroupStart + 200)
            itemRectSnippet shouldContain "rx="

            val customerGroupStart = svg.indexOf("id=\"customer\"")
            val customerRectSnippet = svg.substring(customerGroupStart, customerGroupStart + 200)
            customerRectSnippet shouldNotContain "rx="
            SampleOutput.write("erm/idef1x-dependent-vs-independent-entity.svg", svg)
        }

        "identifying relationship's target entity renders with rounded corners even when not marked weak" {
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
                    kind = RelationshipKind.IDENTIFYING,
                )
            val model = ErmModel(name = "Shop", entities = listOf(customer, order), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "customer" to Rect(Point(20f, 20f), Size(180f, 90f)),
                            "order" to Rect(Point(260f, 20f), Size(180f, 90f)),
                        ),
                    edges = listOf("rel1" to EdgeRoute.Direct(Point(200f, 65f), Point(260f, 65f))),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), SvgRenderOptions(prettyPrint = false))

            val orderGroupStart = svg.indexOf("id=\"order\"")
            val orderRectSnippet = svg.substring(orderGroupStart, orderGroupStart + 200)
            orderRectSnippet shouldContain "rx="
            SampleOutput.write("erm/idef1x-identifying-target-rounded.svg", svg)
        }

        "category renders a discriminator circle; complete gets two completeness bars" {
            val party = ErmEntity(id = "party", name = "Party", attributes = listOf(pk("id")))
            val person = ErmEntity(id = "person", name = "Person", attributes = emptyList())
            val org = ErmEntity(id = "org", name = "Organization", attributes = emptyList())
            val category =
                ErmCategory(
                    id = "category_0",
                    name = "PartyType",
                    supertypeEntityId = "party",
                    subtypeEntityIds = listOf("person", "org"),
                    complete = true,
                )
            val model = ErmModel(name = "Org", entities = listOf(party, person, org), categories = listOf(category))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val circleNodeId = ErmIdef1xLayoutBridge.CATEGORY_NODE_PREFIX + "category_0"
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "party" to Rect(Point(20f, 20f), Size(160f, 60f)),
                            circleNodeId to Rect(Point(80f, 140f), Size(24f, 24f)),
                            "person" to Rect(Point(20f, 220f), Size(160f, 60f)),
                            "org" to Rect(Point(220f, 220f), Size(160f, 60f)),
                        ),
                    edges =
                        listOf(
                            (ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUP_PREFIX + "category_0") to
                                EdgeRoute.Direct(Point(100f, 80f), Point(92f, 140f)),
                            (ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUB_PREFIX + "category_0::person") to
                                EdgeRoute.Direct(Point(92f, 164f), Point(100f, 220f)),
                            (ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUB_PREFIX + "category_0::org") to
                                EdgeRoute.Direct(Point(92f, 164f), Point(300f, 220f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-erm-idef1x-category"
            svg shouldContain "kuml-erm-idef1x-completeness"
            val completenessBarCount = Regex("class=\"kuml-erm-idef1x-completeness\"").findAll(svg).count()
            completenessBarCount shouldBe 2
            SampleOutput.write("erm/idef1x-category-complete.svg", svg)
        }

        "incomplete category renders a single completeness bar" {
            val party = ErmEntity(id = "party", name = "Party", attributes = listOf(pk("id")))
            val person = ErmEntity(id = "person", name = "Person", attributes = emptyList())
            val category =
                ErmCategory(
                    id = "category_0",
                    name = "PartyType",
                    supertypeEntityId = "party",
                    subtypeEntityIds = listOf("person"),
                    complete = false,
                )
            val model = ErmModel(name = "Org", entities = listOf(party, person), categories = listOf(category))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val circleNodeId = ErmIdef1xLayoutBridge.CATEGORY_NODE_PREFIX + "category_0"
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "party" to Rect(Point(20f, 20f), Size(160f, 60f)),
                            circleNodeId to Rect(Point(80f, 140f), Size(24f, 24f)),
                            "person" to Rect(Point(20f, 220f), Size(160f, 60f)),
                        ),
                    edges =
                        listOf(
                            (ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUP_PREFIX + "category_0") to
                                EdgeRoute.Direct(Point(100f, 80f), Point(92f, 140f)),
                            (ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUB_PREFIX + "category_0::person") to
                                EdgeRoute.Direct(Point(92f, 164f), Point(100f, 220f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            val completenessBarCount = Regex("class=\"kuml-erm-idef1x-completeness\"").findAll(svg).count()
            completenessBarCount shouldBe 1
            SampleOutput.write("erm/idef1x-category-incomplete.svg", svg)
        }

        "no raw XML entities leak into rendered text" {
            val customer = ErmEntity(id = "customer", name = "Customer's Table", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout = layoutOf("customer" to Rect(Point(20f, 20f), Size(200f, 90f)))

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldNotContain "&amp;apos;"
            svg shouldNotContain "&amp;lt;"
            SampleOutput.write("erm/idef1x-xml-escape-guard.svg", svg)
        }

        "deterministic output — same input renders byte-identically" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout = layoutOf("customer" to Rect(Point(20f, 20f), Size(180f, 90f)))

            val one = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            one shouldBe two
        }

        "IDEF1X no longer throws (regression guard)" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.MARTIN)
            val layout = layoutOf("customer" to Rect(Point(20f, 20f), Size(180f, 90f)))

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), notation = ErmNotation.IDEF1X)

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
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X)
            val layout =
                layoutOf(
                    nodes = listOf("category" to Rect(Point(200f, 100f), Size(180f, 120f))),
                    edges =
                        listOf(
                            "rel1" to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(200f, 140f),
                                    target = Point(200f, 190f),
                                    waypoints = listOf(Point(180f, 140f), Point(180f, 190f)),
                                    cornerRadiusPx = 6f,
                                ),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), SvgRenderOptions(prettyPrint = false))

            val nameLabel = edgeLabels(svg).single { it.text == "subcategory of" }
            // V3.4.x — see ErmMartinSvgTest's matching test for the rationale:
            // ERM self-loops now route through SelfLoopRouter, which bulges
            // outward from the node's RIGHT edge (x=380 = origin.x=200 + width=180).
            nameLabel.textAnchor shouldBe "start"
            (nameLabel.x >= 380f) shouldBe true
            SampleOutput.write("erm/idef1x-self-loop-name-label-no-overflow.svg", svg)
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
