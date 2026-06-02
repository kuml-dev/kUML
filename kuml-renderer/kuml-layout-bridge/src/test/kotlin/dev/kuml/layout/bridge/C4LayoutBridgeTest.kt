package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.layout.GroupId
import dev.kuml.layout.NodeId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class C4LayoutBridgeTest :
    FunSpec({

        test("C4LayoutBridge converts a container diagram") {
            val system = C4SoftwareSystem(id = "sys1", name = "MySystem")
            val containerA = C4Container(id = "cA", name = "Web App", system = "sys1")
            val containerB = C4Container(id = "cB", name = "Database", system = "sys1")
            val rel =
                C4Relationship(
                    id = "rel1",
                    source = "cA",
                    target = "cB",
                    label = "Reads from",
                )
            val model =
                C4Model(
                    id = "model",
                    name = "Test Model",
                    elements = listOf(system, containerA, containerB),
                    relationships = listOf(rel),
                )
            val diagram =
                ContainerDiagram(
                    id = "diag1",
                    name = "Container View",
                    system = "sys1",
                    elements = listOf("cA", "cB"),
                    relationships = listOf("rel1"),
                )

            val graph = C4LayoutBridge.toLayoutGraph(diagram, model)

            // 1 Group (system)
            graph.groups shouldHaveSize 1
            graph.groups[0].id shouldBe GroupId("sys1")

            // 2 Nodes with groupId pointing to system
            graph.nodes shouldHaveSize 2
            graph.nodes.forEach { node ->
                node.groupId shouldBe GroupId("sys1")
            }
            val nodeIds = graph.nodes.map { it.id }
            nodeIds shouldBe listOf(NodeId("cA"), NodeId("cB"))

            // 1 Edge
            graph.edges shouldHaveSize 1
            val edge = graph.edges[0]
            edge.source.nodeId shouldBe NodeId("cA")
            edge.target.nodeId shouldBe NodeId("cB")
        }

        test("C4LayoutBridge skips unresolvable relationship ids") {
            val containerA = C4Container(id = "cA", name = "Service A")
            val containerB = C4Container(id = "cB", name = "Service B")
            // Relationship that references a non-existent element ID
            val badRel =
                C4Relationship(
                    id = "relBad",
                    source = "cA",
                    target = "nonexistent",
                    label = "Calls",
                )
            val model =
                C4Model(
                    id = "model",
                    name = "Test Model",
                    elements = listOf(containerA, containerB),
                    relationships = listOf(badRel),
                )
            val diagram =
                ContainerDiagram(
                    id = "diag1",
                    name = "Container View",
                    system = "sys1",
                    elements = listOf("cA", "cB"),
                    relationships = listOf("relBad"),
                )

            // Should not throw — just silently skip the bad relationship
            val graph = C4LayoutBridge.toLayoutGraph(diagram, model)

            graph.edges shouldHaveSize 0
        }
    })
