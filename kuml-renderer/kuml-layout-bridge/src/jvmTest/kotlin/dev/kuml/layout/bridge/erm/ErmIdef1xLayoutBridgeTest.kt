package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCategory
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.ErmView
import dev.kuml.layout.EdgeId
import dev.kuml.layout.NodeId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ErmIdef1xLayoutBridgeTest :
    FunSpec({

        val customer =
            ErmEntity(
                id = "customer",
                name = "Customer",
                attributes = listOf(ErmAttribute(id = "customer_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
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

        test("without categories the graph is identical to ErmLayoutBridge's") {
            val diagram = ErmDiagram(name = "Overview")
            val idef1xGraph = ErmIdef1xLayoutBridge.toLayoutGraph(model, diagram)
            val martinGraph = ErmLayoutBridge.toLayoutGraph(model, diagram)

            idef1xGraph.nodes.map { it.id } shouldBe martinGraph.nodes.map { it.id }
            idef1xGraph.edges.map { it.id } shouldBe martinGraph.edges.map { it.id }
            idef1xGraph.groups shouldBe emptyList()
        }

        test("elementIds filters to a subset — relationships with a hidden endpoint are dropped") {
            val diagram = ErmDiagram(name = "CustomerOnly", elementIds = listOf("customer"))
            val graph = ErmIdef1xLayoutBridge.toLayoutGraph(model, diagram)

            graph.nodes shouldHaveSize 1
            graph.nodes[0].id shouldBe NodeId("customer")
            graph.edges shouldHaveSize 0
        }

        test("showViews = true adds ErmView nodes; false omits them") {
            val view = ErmView(id = "view_big", name = "big_orders", query = "SELECT * FROM \"order\"")
            val modelWithView = model.copy(views = listOf(view))

            val shown = ErmIdef1xLayoutBridge.toLayoutGraph(modelWithView, ErmDiagram(name = "WithViews", showViews = true))
            shown.nodes.map { it.id } shouldBe listOf(NodeId("customer"), NodeId("order"), NodeId("view_big"))

            val hidden = ErmIdef1xLayoutBridge.toLayoutGraph(modelWithView, ErmDiagram(name = "NoViews", showViews = false))
            hidden.nodes.map { it.id } shouldBe listOf(NodeId("customer"), NodeId("order"))
        }

        test("a category adds exactly one circle node, one supertype edge, and one edge per subtype") {
            val person = ErmEntity(id = "person", name = "Person", attributes = emptyList())
            val org = ErmEntity(id = "org", name = "Organization", attributes = emptyList())
            val party =
                ErmEntity(
                    id = "party",
                    name = "Party",
                    attributes = listOf(ErmAttribute(id = "party_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
                )
            val category =
                ErmCategory(
                    id = "category_0",
                    name = "PartyType",
                    supertypeEntityId = "party",
                    subtypeEntityIds = listOf("person", "org"),
                    complete = true,
                )
            val categorizedModel =
                ErmModel(name = "Org", entities = listOf(party, person, org), categories = listOf(category))
            val diagram = ErmDiagram(name = "Overview")

            val graph = ErmIdef1xLayoutBridge.toLayoutGraph(categorizedModel, diagram)

            // 3 entities + 1 category circle
            graph.nodes shouldHaveSize 4
            val circleId = NodeId(ErmIdef1xLayoutBridge.CATEGORY_NODE_PREFIX + "category_0")
            graph.nodes.map { it.id } shouldContain circleId
            graph.nodes.first { it.id == circleId }.intrinsicSize.width shouldBe ErmIdef1xLayoutBridge.CATEGORY_CIRCLE_SIZE
            graph.nodes.first { it.id == circleId }.intrinsicSize.height shouldBe ErmIdef1xLayoutBridge.CATEGORY_CIRCLE_SIZE

            val supEdge = graph.edges.first { it.id == EdgeId(ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUP_PREFIX + "category_0") }
            supEdge.source.nodeId shouldBe NodeId("party")
            supEdge.target.nodeId shouldBe circleId

            val subEdges = graph.edges.filter { it.id.value.startsWith(ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUB_PREFIX) }
            subEdges shouldHaveSize 2
            subEdges.forEach { it.source.nodeId shouldBe circleId }
            subEdges.map { it.target.nodeId } shouldBe listOf(NodeId("person"), NodeId("org"))
        }

        test("elementIds filters category endpoints — category is dropped when the supertype is hidden") {
            val person = ErmEntity(id = "person", name = "Person", attributes = emptyList())
            val party =
                ErmEntity(
                    id = "party",
                    name = "Party",
                    attributes = listOf(ErmAttribute(id = "party_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
                )
            val category =
                ErmCategory(id = "category_0", name = "PartyType", supertypeEntityId = "party", subtypeEntityIds = listOf("person"))
            val categorizedModel = ErmModel(name = "Org", entities = listOf(party, person), categories = listOf(category))

            val diagram = ErmDiagram(name = "PersonOnly", elementIds = listOf("person"))
            val graph = ErmIdef1xLayoutBridge.toLayoutGraph(categorizedModel, diagram)

            graph.nodes shouldHaveSize 1
            graph.nodes[0].id shouldBe NodeId("person")
            graph.edges shouldHaveSize 0
        }

        test("groups is always empty") {
            val graph = ErmIdef1xLayoutBridge.toLayoutGraph(model, ErmDiagram(name = "Overview"))
            graph.groups shouldBe emptyList()
        }
    })
