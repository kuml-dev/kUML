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

        // ── Self-loop / edge-label-collision regression guards (fix/erm-martin-edge-label-collision) ──

        "self-referential relationship name label does not overflow into the entity box" {
            val svg = selfLoopSvg()

            val nameLabel = edgeLabels(svg).single { it.text == "subcategory of" }
            // Box left edge sits at x=200 (see selfLoopSvg()); the label must grow
            // AWAY from the box (text-anchor="end") and its anchor x must not be
            // to the right of the border — before the fix this was
            // text-anchor="middle" at x≈180, painting glyphs across x=200..223.
            nameLabel.textAnchor shouldBe "end"
            (nameLabel.x <= 200f) shouldBe true
            SampleOutput.write("erm/self-loop-name-label-no-overflow.svg", svg)
        }

        "self-loop role labels occupy distinct vertical bands from the name label" {
            val svg = selfLoopSvg()
            val labels = edgeLabels(svg)

            val parentY = labels.single { it.text == "parent" }.y
            val nameY = labels.single { it.text == "subcategory of" }.y
            val childY = labels.single { it.text == "child" }.y

            (kotlin.math.abs(parentY - nameY) >= 12f) shouldBe true
            (kotlin.math.abs(nameY - childY) >= 12f) shouldBe true
            (kotlin.math.abs(parentY - childY) >= 12f) shouldBe true
        }

        "self-loop determinism — same input renders byte-identically" {
            val one = selfLoopSvg()
            val two = selfLoopSvg()
            one shouldBe two
        }

        "vertical-segment name label is pushed to the side, not centered on the line" {
            val parent = ErmEntity(id = "parent", name = "Parent", attributes = listOf(pk("id")))
            val child = ErmEntity(id = "child", name = "Child", attributes = listOf(pk("id")))
            val rel =
                ErmRelationship(
                    id = "rel1",
                    name = "contains",
                    sourceEntityId = "parent",
                    targetEntityId = "child",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                )
            val model = ErmModel(name = "Tree", entities = listOf(parent, child), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview")
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            "parent" to Rect(Point(50f, 20f), Size(160f, 90f)),
                            "child" to Rect(Point(50f, 300f), Size(160f, 90f)),
                        ),
                    edges =
                        listOf(
                            "rel1" to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(130f, 110f),
                                    target = Point(130f, 300f),
                                    waypoints = emptyList(),
                                    cornerRadiusPx = 6f,
                                ),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), SvgRenderOptions(prettyPrint = false))

            val nameLabel = edgeLabels(svg).single { it.text == "contains" }
            nameLabel.textAnchor shouldBe "start"
            (nameLabel.x > 130f) shouldBe true
            SampleOutput.write("erm/vertical-segment-name-label.svg", svg)
        }
    })

private fun selfLoopSvg(): String {
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
    val diagram = ErmDiagram(name = "Overview")
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
    return KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), SvgRenderOptions(prettyPrint = false))
}

/**
 * One `kuml-edge-label` `<text>` element (the coloured pass, not its halo
 * twin). Internal (not private) so `ErmBachmanSvgTest`/`ErmIdef1xSvgTest` can
 * reuse it for their own self-loop overflow guard tests instead of
 * duplicating the parsing logic.
 */
internal data class EdgeLabelInfo(
    val x: Float,
    val y: Float,
    val textAnchor: String,
    val text: String,
)

/**
 * Extracts every `kuml-edge-label` (non-halo) `<text>` element from [svg]. The
 * halo pass shares the same x/y/text-anchor but carries the
 * `kuml-edge-label-halo` class, whose value does not match the exact
 * `class="kuml-edge-label"` literal below (different closing quote position).
 */
internal fun edgeLabels(svg: String): List<EdgeLabelInfo> {
    val regex =
        Regex("""<text class="kuml-edge-label" x="([^"]+)" y="([^"]+)" text-anchor="([^"]+)">([^<]*)</text>""")
    return regex.findAll(svg).map { m ->
        val (x, y, anchor, text) = m.destructured
        EdgeLabelInfo(x.toFloat(), y.toFloat(), anchor, text)
    }.toList()
}

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
