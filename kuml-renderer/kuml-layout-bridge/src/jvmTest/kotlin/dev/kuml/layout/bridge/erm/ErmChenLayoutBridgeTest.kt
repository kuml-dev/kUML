package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.ErmView
import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class ErmChenLayoutBridgeTest :
    FunSpec({

        val customer =
            ErmEntity(
                id = "customer",
                name = "Customer",
                attributes =
                    listOf(
                        ErmAttribute(id = "customer_id", name = "id", type = ErmDataType.Uuid, primaryKey = true),
                        ErmAttribute(id = "customer_email", name = "email", type = ErmDataType.Varchar(255)),
                    ),
            )
        val order =
            ErmEntity(
                id = "order",
                name = "Order",
                attributes = listOf(ErmAttribute(id = "order_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
            )
        val rel =
            ErmRelationship(
                id = "rel_places",
                name = "places",
                sourceEntityId = "customer",
                targetEntityId = "order",
                sourceCardinality = Cardinality.ONE,
                targetCardinality = Cardinality.ZERO_MANY,
            )
        val model = ErmModel(name = "Shop", entities = listOf(customer, order), relationships = listOf(rel))

        test("every entity, attribute, and relationship becomes its own node with the correct prefix") {
            val diagram = ErmDiagram(name = "Overview")
            val graph = ErmChenLayoutBridge.toChenLayoutGraph(model, diagram)

            // 2 entities + 2 attributes on customer + 1 attribute on order + 1 relationship = 6 nodes
            graph.nodes shouldHaveSize 6
            val nodeIds = graph.nodes.map { it.id }
            nodeIds shouldContain NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer")
            nodeIds shouldContain NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "order")
            nodeIds shouldContain NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "customer_id")
            nodeIds shouldContain NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "customer_email")
            nodeIds shouldContain NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "order_id")
            nodeIds shouldContain NodeId(ErmChenLayoutBridge.REL_PREFIX + "rel_places")
        }

        test("attribute edges connect each entity to its own attributes, one edge per attribute") {
            val diagram = ErmDiagram(name = "Overview")
            val graph = ErmChenLayoutBridge.toChenLayoutGraph(model, diagram)

            val attrEdges = graph.edges.filter { it.id.value.startsWith(ErmChenLayoutBridge.ATTR_EDGE_PREFIX) }
            attrEdges shouldHaveSize 3 // 2 customer attrs + 1 order attr

            val customerIdEdge =
                attrEdges.first { it.id == EdgeId(ErmChenLayoutBridge.ATTR_EDGE_PREFIX + "customer::customer_id") }
            customerIdEdge.source.nodeId shouldBe NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer")
            customerIdEdge.target.nodeId shouldBe NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "customer_id")
        }

        test("relationship gets a diamond node plus one edge to each entity end") {
            val diagram = ErmDiagram(name = "Overview")
            val graph = ErmChenLayoutBridge.toChenLayoutGraph(model, diagram)

            val srcEdge = graph.edges.first { it.id == EdgeId(ErmChenLayoutBridge.REL_EDGE_SRC_PREFIX + "rel_places") }
            srcEdge.source.nodeId shouldBe NodeId(ErmChenLayoutBridge.REL_PREFIX + "rel_places")
            srcEdge.target.nodeId shouldBe NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer")

            val tgtEdge = graph.edges.first { it.id == EdgeId(ErmChenLayoutBridge.REL_EDGE_TGT_PREFIX + "rel_places") }
            tgtEdge.source.nodeId shouldBe NodeId(ErmChenLayoutBridge.REL_PREFIX + "rel_places")
            tgtEdge.target.nodeId shouldBe NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "order")
        }

        test("elementIds filters to a subset — attributes/relationship of hidden entities are dropped") {
            val diagram = ErmDiagram(name = "CustomerOnly", elementIds = listOf("customer"))
            val graph = ErmChenLayoutBridge.toChenLayoutGraph(model, diagram)

            // 1 entity + its 2 attributes = 3 nodes; no relationship (order is hidden)
            graph.nodes shouldHaveSize 3
            graph.nodes.map { it.id } shouldBe
                listOf(
                    NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer"),
                    NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "customer_id"),
                    NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "customer_email"),
                )
            graph.edges shouldHaveSize 2 // just the two attribute edges
        }

        test("showViews = true adds ErmView nodes with no edges; false omits them") {
            val view = ErmView(id = "view_big", name = "big_orders", query = "SELECT * FROM \"order\"")
            val modelWithView = model.copy(views = listOf(view))

            val shown = ErmChenLayoutBridge.toChenLayoutGraph(modelWithView, ErmDiagram(name = "WithViews", showViews = true))
            shown.nodes.map { it.id } shouldContain NodeId(ErmChenLayoutBridge.VIEW_PREFIX + "view_big")

            val hidden = ErmChenLayoutBridge.toChenLayoutGraph(modelWithView, ErmDiagram(name = "NoViews", showViews = false))
            hidden.nodes.map { it.id } shouldBe
                listOf(
                    NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "customer"),
                    NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "customer_id"),
                    NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "customer_email"),
                    NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "order"),
                    NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "order_id"),
                    NodeId(ErmChenLayoutBridge.REL_PREFIX + "rel_places"),
                )
        }

        test("self-referencing relationship produces a diamond with both edges landing on the same entity") {
            val employee =
                ErmEntity(
                    id = "employee",
                    name = "Employee",
                    attributes = listOf(ErmAttribute(id = "emp_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
                )
            val managerRel =
                ErmRelationship(
                    id = "rel_manages",
                    name = "manages",
                    sourceEntityId = "employee",
                    targetEntityId = "employee",
                    sourceCardinality = Cardinality.ZERO_ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                )
            val selfModel = ErmModel(name = "Org", entities = listOf(employee), relationships = listOf(managerRel))
            val graph = ErmChenLayoutBridge.toChenLayoutGraph(selfModel, ErmDiagram(name = "Org"))

            // 1 entity + 1 attribute + 1 relationship diamond = 3 nodes
            graph.nodes shouldHaveSize 3
            val relEdges = graph.edges.filter { it.id.value.startsWith("chen-reledge") }
            relEdges shouldHaveSize 2
            relEdges.forEach { it.target.nodeId shouldBe NodeId(ErmChenLayoutBridge.ENTITY_PREFIX + "employee") }
        }

        test("groups is always empty") {
            val graph = ErmChenLayoutBridge.toChenLayoutGraph(model, ErmDiagram(name = "Overview"))
            graph.groups shouldBe emptyList()
        }

        test("real ELK run: ErmChenSizeProvider's oval width is only visible through the bridge, not a hardcoded LayoutResult") {
            // CLAUDE.md pitfall guard: a hardcoded LayoutResult would never
            // exercise ErmChenSizeProvider at all. This test goes through the
            // actual ErmChenLayoutBridge → ELK pipeline to prove the size
            // provider's output really reaches the layout engine.
            val longAttr =
                ErmAttribute(id = "descriptive_col", name = "a_very_long_descriptive_column_name", type = ErmDataType.Varchar(255))
            val wideEntity = ErmEntity(id = "wide", name = "Wide", attributes = listOf(longAttr))
            val wideModel = ErmModel(name = "Wide", entities = listOf(wideEntity))
            val diagram = ErmDiagram(name = "Wide")

            val engine = ElkLayoutEngineProvider().engine()
            val sizeProvider = ErmChenSizeProvider(wideModel, diagram)
            val graph = ErmChenLayoutBridge.toChenLayoutGraph(wideModel, diagram, sizeProvider)
            val layout = engine.layout(graph, LayoutHints.DEFAULT)

            val ovalBounds =
                layout.nodes.entries
                    .first { it.key == NodeId(ErmChenLayoutBridge.ATTR_PREFIX + "descriptive_col") }
                    .value.bounds
            ovalBounds.size.width shouldBeGreaterThan ErmChenSizeProvider.OVAL_MIN_W
        }
    })
