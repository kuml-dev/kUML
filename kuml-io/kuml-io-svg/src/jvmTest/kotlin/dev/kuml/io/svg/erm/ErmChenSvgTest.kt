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
import dev.kuml.layout.bridge.erm.ErmChenLayoutBridge
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Structural + smoke tests for the ERM/Chen SVG renderer (V3.4.4). Uses a
 * hand-built [LayoutResult] with [ErmChenLayoutBridge]'s synthetic
 * id-prefixes — this is fine here because these tests exercise the
 * *renderer's* drawing/dispatch logic, not content-aware sizing (that lives
 * in `ErmChenLayoutBridgeTest`, which goes through the real
 * `ErmChenLayoutBridge` → ELK pipeline, per the CLAUDE.md "no hardcoded
 * LayoutResult" pitfall for sizing tests specifically).
 *
 * Each test also writes its SVG (+ auto-generated PNG) to
 * `kuml-io-svg/build/sample-output/erm/chen-<test-name>.svg` for visual
 * review.
 */
class ErmChenSvgTest :
    StringSpec({

        "entity renders its name as a title-only box, no empty canvas" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(
                    nodes = listOf(NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer") to Rect(Point(20f, 20f), Size(160f, 44f))),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "Customer"
            svg shouldContain "kuml-erm-entity"
            SampleOutput.write("erm/chen-entity-title-only.svg", svg)
        }

        "attribute renders as an ellipse; primary key gets an underline" {
            val idAttr = pk("id")
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(idAttr))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer") to Rect(Point(20f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "id") to Rect(Point(20f, 100f), Size(100f, 40f)),
                        ),
                    edges =
                        listOf(
                            EdgeId(ErmChenLayoutBridge.ATTR_EDGE_PREFIX + "customer::id") to
                                EdgeRoute.Direct(Point(70f, 64f), Point(70f, 100f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<ellipse"
            svg shouldContain "kuml-erm-chen-attribute"
            svg shouldContain "kuml-erm-pk-underline"
            SampleOutput.write("erm/chen-attribute-oval-pk.svg", svg)
        }

        "relationship renders as a diamond (polygon)" {
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
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer") to Rect(Point(20f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "order") to Rect(Point(300f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.REL_PREFIX + "rel1") to Rect(Point(160f, 120f), Size(110f, 60f)),
                        ),
                    edges =
                        listOf(
                            // Bug-fix V3.4.6: sourceEntity -> diamond (entity is this
                            // route's source, diamond is its target).
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_SRC_PREFIX + "rel1") to
                                EdgeRoute.Direct(Point(100f, 64f), Point(215f, 150f)),
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_TGT_PREFIX + "rel1") to
                                EdgeRoute.Direct(Point(215f, 150f), Point(380f, 64f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<polygon"
            svg shouldContain "kuml-erm-chen-relationship\""
            svg shouldContain "places"
            SampleOutput.write("erm/chen-relationship-diamond.svg", svg)
        }

        "IDENTIFYING relationship draws an inner diamond (double border)" {
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val item = ErmEntity(id = "item", name = "OrderItem", weak = true, attributes = listOf(pk("id")))
            val rel =
                ErmRelationship(
                    id = "rel2",
                    name = "contains",
                    sourceEntityId = "order",
                    targetEntityId = "item",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    kind = RelationshipKind.IDENTIFYING,
                )
            val model = ErmModel(name = "Shop", entities = listOf(order, item), relationships = listOf(rel))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "order") to Rect(Point(20f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "item") to Rect(Point(300f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.REL_PREFIX + "rel2") to Rect(Point(160f, 120f), Size(110f, 60f)),
                        ),
                    edges =
                        listOf(
                            // Bug-fix V3.4.6: sourceEntity -> diamond (entity is this
                            // route's source, diamond is its target).
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_SRC_PREFIX + "rel2") to
                                EdgeRoute.Direct(Point(100f, 64f), Point(215f, 150f)),
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_TGT_PREFIX + "rel2") to
                                EdgeRoute.Direct(Point(215f, 150f), Point(380f, 64f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-erm-chen-relationship-inner"
            SampleOutput.write("erm/chen-identifying-relationship.svg", svg)
        }

        "weak entity draws a second, inner rect (double border)" {
            val order = ErmEntity(id = "order", name = "Order", attributes = listOf(pk("id")))
            val item = ErmEntity(id = "item", name = "OrderItem", weak = true, attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(order, item))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "order") to Rect(Point(20f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "item") to Rect(Point(300f, 20f), Size(160f, 44f)),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "kuml-erm-entity-inner"
            SampleOutput.write("erm/chen-weak-entity.svg", svg)
        }

        "cardinality label (1/N) renders near the entity end of a diamond connector" {
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
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(
                    nodes =
                        listOf(
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer") to Rect(Point(20f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "order") to Rect(Point(300f, 20f), Size(160f, 44f)),
                            NodeId(ErmChenLayoutBridge.REL_PREFIX + "rel1") to Rect(Point(160f, 120f), Size(110f, 60f)),
                        ),
                    edges =
                        listOf(
                            // Bug-fix V3.4.6: sourceEntity -> diamond (entity is this
                            // route's source, diamond is its target).
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_SRC_PREFIX + "rel1") to
                                EdgeRoute.Direct(Point(100f, 64f), Point(215f, 150f)),
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_TGT_PREFIX + "rel1") to
                                EdgeRoute.Direct(Point(215f, 150f), Point(380f, 64f)),
                        ),
                )

            // Non-pretty output so the cardinality glyph's text content sits
            // directly between its tag brackets (`>1<`) — with pretty-printing
            // the label would be on its own indented line, making a substring
            // check on the rendered text unreliable.
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), SvgRenderOptions(prettyPrint = false))

            // Bug-fix (fix/erm-chen-label-collisions, V3.4.7): cardinality
            // labels now route through the shared `renderEdgeLabelWithHalo`
            // helper (like every other ERM edge label) instead of a bespoke
            // `kuml-erm-chen-cardinality`-classed <text> with no halo.
            svg shouldContain "kuml-edge-label"
            svg shouldContain ">1<"
            svg shouldContain ">N<"
            SampleOutput.write("erm/chen-cardinality-labels.svg", svg)
        }

        "cardinality labels on a converging hub clear the entity box, come off the line, and fan apart" {
            val address = ErmEntity(id = "address", name = "Address", attributes = listOf(pk("id")))
            val supplierA = ErmEntity(id = "supplierA", name = "SupplierA", attributes = listOf(pk("id")))
            val supplierB = ErmEntity(id = "supplierB", name = "SupplierB", attributes = listOf(pk("id")))
            val supplierC = ErmEntity(id = "supplierC", name = "SupplierC", attributes = listOf(pk("id")))
            val shipment = ErmEntity(id = "shipment", name = "Shipment", attributes = listOf(pk("id")))

            fun hubRel(
                id: String,
                sourceEntityId: String,
                targetEntityId: String,
            ) = ErmRelationship(
                id = id,
                name = null,
                sourceEntityId = sourceEntityId,
                targetEntityId = targetEntityId,
                sourceCardinality = Cardinality.ONE,
                targetCardinality = Cardinality.ZERO_MANY,
            )

            // Three relationships converge on Address as TARGET (diamonds sit
            // above, near-vertical connectors into the top border) and one
            // has Address as SOURCE (diamond sits below, near-vertical
            // connector leaving the bottom border) — reproducing both the
            // pre-fix source-into-box case and the target-on-line case in one
            // fixture.
            val relA = hubRel("relA", "supplierA", "address")
            val relB = hubRel("relB", "supplierB", "address")
            val relC = hubRel("relC", "supplierC", "address")
            val relD = hubRel("relD", "address", "shipment")

            val model =
                ErmModel(
                    name = "Shop",
                    entities = listOf(address, supplierA, supplierB, supplierC, shipment),
                    relationships = listOf(relA, relB, relC, relD),
                )
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)

            val addressBounds = Rect(Point(300f, 400f), Size(160f, 44f))
            val layout =
                layoutOf(
                    nodes = listOf(NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "address") to addressBounds),
                    edges =
                        listOf(
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_TGT_PREFIX + "relA") to
                                EdgeRoute.Direct(Point(330f, 250f), Point(340f, 400f)),
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_TGT_PREFIX + "relB") to
                                EdgeRoute.Direct(Point(380f, 250f), Point(380f, 400f)),
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_TGT_PREFIX + "relC") to
                                EdgeRoute.Direct(Point(430f, 250f), Point(420f, 400f)),
                            EdgeId(ErmChenLayoutBridge.REL_EDGE_SRC_PREFIX + "relD") to
                                EdgeRoute.Direct(Point(380f, 444f), Point(380f, 550f)),
                        ),
                )

            // paddingPx = 0f so the rendered coordinates match the layout's
            // raw coordinates 1:1 (SvgDocument otherwise shifts every node/
            // route by +paddingPx, which would have to be re-added to every
            // pixel comparison below).
            val renderOptions = SvgRenderOptions(prettyPrint = false, paddingPx = 0f)
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), renderOptions)
            val labels = edgeLabels(svg).filter { it.text == "1" || it.text == "N" }
            labels shouldHaveSize 4

            // Assertion 1 — title clearance: no label falls inside the Address
            // box, expanded by the clearance margin. Fails on pre-fix master:
            // the SOURCE-side "1" label (relD) sits ~14px inside the box, on
            // the title.
            val margin = ErmChenSizing.CARDINALITY_TITLE_CLEARANCE_PX
            val minX = addressBounds.origin.x - margin
            val minY = addressBounds.origin.y - margin
            val maxX = addressBounds.origin.x + addressBounds.size.width + margin
            val maxY = addressBounds.origin.y + addressBounds.size.height + margin
            for (l in labels) {
                val inside = l.x in minX..maxX && l.y in minY..maxY
                inside shouldBe false
            }

            // Assertion 2 — perpendicular offset off the line: relD's
            // connector is exactly vertical (x=380 at both ends), so its
            // label must be pushed sideways, off the edge-x, with a
            // non-"middle" anchor. Fails on pre-fix master: the label sits
            // exactly on x=380 with text-anchor="middle".
            val relDLabel = labels.single { it.text == "1" }
            (kotlin.math.abs(relDLabel.x - 380f) >= ErmChenSizing.CARDINALITY_LABEL_PERP_PX) shouldBe true
            (relDLabel.textAnchor != "middle") shouldBe true

            // Assertion 3 — hub separation: the three converging TARGET
            // labels ("N") must not land on top of each other.
            val hubLabels = labels.filter { it.text == "N" }
            hubLabels shouldHaveSize 3
            for (i in hubLabels.indices) {
                for (j in i + 1 until hubLabels.size) {
                    val dx = hubLabels[i].x - hubLabels[j].x
                    val dy = hubLabels[i].y - hubLabels[j].y
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    (dist >= ErmChenSizing.CARDINALITY_LABEL_STACK_PX) shouldBe true
                }
            }

            // Assertion 4 — determinism.
            val again = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), renderOptions)
            svg shouldBe again

            SampleOutput.write("erm/chen-hub-cardinality-labels.svg", svg)
        }

        "no raw XML entities leak into rendered text" {
            val customer = ErmEntity(id = "customer", name = "Customer's Table", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(nodes = listOf(NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer") to Rect(Point(20f, 20f), Size(200f, 44f))))

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldNotContain "&amp;apos;"
            svg shouldNotContain "&amp;lt;"
            SampleOutput.write("erm/chen-xml-escape-guard.svg", svg)
        }

        "IDEF1X no longer throws (regression guard, V3.4.5)" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.MARTIN)
            val layout =
                layoutOf(nodes = listOf(NodeId("customer") to Rect(Point(20f, 20f), Size(180f, 90f))))

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme(), notation = ErmNotation.IDEF1X)

            svg shouldContain "kuml-erm-entity"
        }

        "deterministic output — same input renders byte-identically" {
            val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(pk("id")))
            val model = ErmModel(name = "Shop", entities = listOf(customer))
            val diagram = ErmDiagram(name = "Overview", notation = ErmNotation.CHEN)
            val layout =
                layoutOf(nodes = listOf(NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer") to Rect(Point(20f, 20f), Size(180f, 44f))))

            val one = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            one shouldBe two
        }
    })

private fun pk(name: String): ErmAttribute = ErmAttribute(id = name, name = name, type = ErmDataType.Uuid, primaryKey = true)

private fun layoutOf(
    nodes: List<Pair<NodeId, Rect>> = emptyList(),
    edges: List<Pair<EdgeId, EdgeRoute>> = emptyList(),
): LayoutResult {
    val maxX = nodes.maxOfOrNull { it.second.origin.x + it.second.size.width } ?: 200f
    val maxY = nodes.maxOfOrNull { it.second.origin.y + it.second.size.height } ?: 150f
    return LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = 1L,
        canvas = Size(maxX + 20f, maxY + 20f),
        nodes = nodes.associate { (id, rect) -> id to NodeLayout(bounds = rect) },
        edges = edges.associate { (id, route) -> id to route },
        groups = emptyMap(),
    )
}
