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
import dev.kuml.layout.NodeId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ErmLayoutBridgeTest :
    FunSpec({

        val customer = ErmEntity(id = "customer", name = "Customer", attributes = listOf(ErmAttribute(id = "customer_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)))
        val order = ErmEntity(id = "order", name = "Order", attributes = listOf(ErmAttribute(id = "order_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)))
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

        test("maps every visible entity to a LayoutNode and every relationship to a LayoutEdge") {
            val diagram = ErmDiagram(name = "Overview")
            val graph = ErmLayoutBridge.toLayoutGraph(model, diagram)

            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id } shouldBe listOf(NodeId("customer"), NodeId("order"))
            graph.edges shouldHaveSize 1
            graph.edges[0].id shouldBe EdgeId("rel_places")
            graph.edges[0].source.nodeId shouldBe NodeId("customer")
            graph.edges[0].target.nodeId shouldBe NodeId("order")
            graph.groups shouldBe emptyList()
        }

        test("empty elementIds means the whole model — default projection") {
            val diagram = ErmDiagram(name = "Overview", elementIds = emptyList())
            val graph = ErmLayoutBridge.toLayoutGraph(model, diagram)
            graph.nodes shouldHaveSize 2
        }

        test("elementIds filters to a subset — relationships with a hidden endpoint are dropped") {
            val diagram = ErmDiagram(name = "CustomerOnly", elementIds = listOf("customer"))
            val graph = ErmLayoutBridge.toLayoutGraph(model, diagram)

            graph.nodes shouldHaveSize 1
            graph.nodes[0].id shouldBe NodeId("customer")
            graph.edges shouldHaveSize 0
        }

        test("showViews = true adds ErmView nodes; false omits them") {
            val view = ErmView(id = "view_big", name = "big_orders", query = "SELECT * FROM \"order\"")
            val modelWithView = model.copy(views = listOf(view))

            val shown = ErmLayoutBridge.toLayoutGraph(modelWithView, ErmDiagram(name = "WithViews", showViews = true))
            shown.nodes.map { it.id } shouldBe listOf(NodeId("customer"), NodeId("order"), NodeId("view_big"))

            val hidden = ErmLayoutBridge.toLayoutGraph(modelWithView, ErmDiagram(name = "NoViews", showViews = false))
            hidden.nodes.map { it.id } shouldBe listOf(NodeId("customer"), NodeId("order"))
        }

        test("self-referencing relationship produces exactly one edge with identical source/target") {
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
            val graph = ErmLayoutBridge.toLayoutGraph(selfModel, ErmDiagram(name = "Org"))

            graph.nodes shouldHaveSize 1
            graph.edges shouldHaveSize 1
            graph.edges[0].source.nodeId shouldBe NodeId("employee")
            graph.edges[0].target.nodeId shouldBe NodeId("employee")
        }
    })
