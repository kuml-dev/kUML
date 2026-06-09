package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.GroupId
import dev.kuml.layout.NodeId
import dev.kuml.layout.PortId
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UmlLayoutBridgeTest :
    FunSpec({

        test("UmlLayoutBridge converts a minimal class diagram") {
            val classA = UmlClass(id = "classA", name = "ClassA")
            val classB = UmlClass(id = "classB", name = "ClassB")
            val assoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "classA"),
                            UmlAssociationEnd(typeId = "classB"),
                        ),
                )
            val diagram =
                KumlDiagram(
                    name = "Minimal",
                    elements = listOf(classA, classB, assoc),
                )

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            graph.nodes shouldHaveSize 2
            graph.edges shouldHaveSize 1
            graph.groups shouldHaveSize 0

            val nodeIds = graph.nodes.map { it.id }
            nodeIds shouldBe listOf(NodeId("classA"), NodeId("classB"))

            val edge = graph.edges[0]
            edge.source.nodeId shouldBe NodeId("classA")
            edge.target.nodeId shouldBe NodeId("classB")
        }

        test("UmlLayoutBridge wraps classes in a package group") {
            val classA = UmlClass(id = "classA", name = "ClassA")
            val classB = UmlClass(id = "classB", name = "ClassB")
            val pkg =
                UmlPackage(
                    id = "pkg1",
                    name = "com.example",
                    members = listOf(classA, classB),
                )
            val diagram =
                KumlDiagram(
                    name = "Packaged",
                    elements = listOf(pkg),
                )

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            graph.groups shouldHaveSize 1
            graph.groups[0].id shouldBe GroupId("pkg1")

            graph.nodes shouldHaveSize 2
            graph.nodes.forEach { node ->
                node.groupId shouldBe GroupId("pkg1")
            }
        }

        test("UmlLayoutBridge maps generalization as edge") {
            val parent = UmlClass(id = "parent", name = "Animal")
            val child = UmlClass(id = "child", name = "Dog")
            val gen = UmlGeneralization(id = "gen1", specificId = "child", generalId = "parent")
            val diagram =
                KumlDiagram(
                    name = "Generalization",
                    elements = listOf(parent, child, gen),
                )

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            graph.edges shouldHaveSize 1
            val edge = graph.edges[0]
            edge.source.nodeId shouldBe NodeId("child")
            edge.target.nodeId shouldBe NodeId("parent")
            edge.hints shouldNotBe null
        }

        // ── Connector port-ID splitting (V2.0.x fix) ─────────────────────────
        //
        // UmlConnector.end1Id/end2Id are written by ComponentDsl.connect(port1, port2)
        // as "<componentId>::<portName>". The bridge must split that suffix into
        // EndpointRef(nodeId = componentId, portId = portName) so the Grid engine
        // can resolve the source/target nodes (otherwise getValue() throws).

        test("UmlLayoutBridge splits connector port-qualified IDs into node + port") {
            val order =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::events", name = "events")),
                )
            val broker =
                UmlComponent(
                    id = "MessageBroker",
                    name = "MessageBroker",
                    ports = listOf(UmlPort(id = "MessageBroker::pub", name = "pub")),
                )
            val connector =
                UmlConnector(
                    id = "conn::OrderService::events--MessageBroker::pub",
                    end1Id = "OrderService::events",
                    end2Id = "MessageBroker::pub",
                )
            val diagram =
                KumlDiagram(
                    name = "Architecture",
                    elements = listOf(order, broker, connector),
                )

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            // Exactly two nodes — ports must NOT be promoted to sub-nodes.
            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id }.toSet() shouldBe setOf(NodeId("OrderService"), NodeId("MessageBroker"))

            graph.edges shouldHaveSize 1
            val edge = graph.edges[0]
            edge.source.nodeId shouldBe NodeId("OrderService")
            edge.source.portId shouldBe PortId("events")
            edge.target.nodeId shouldBe NodeId("MessageBroker")
            edge.target.portId shouldBe PortId("pub")
        }

        test("UmlLayoutBridge resolves ports on nested components") {
            val inner =
                UmlComponent(
                    id = "OrderRepository",
                    name = "OrderRepository",
                    ports = listOf(UmlPort(id = "OrderRepository::db", name = "db")),
                )
            val outer =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    nestedComponents = listOf(inner),
                )
            val db =
                UmlComponent(
                    id = "Database",
                    name = "Database",
                    ports = listOf(UmlPort(id = "Database::conn", name = "conn")),
                )
            val connector =
                UmlConnector(
                    id = "conn1",
                    end1Id = "OrderRepository::db",
                    end2Id = "Database::conn",
                )
            val diagram =
                KumlDiagram(name = "Nested", elements = listOf(outer, db, connector))

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            graph.edges shouldHaveSize 1
            val edge = graph.edges[0]
            edge.source.nodeId shouldBe NodeId("OrderRepository")
            edge.source.portId shouldBe PortId("db")
            edge.target.nodeId shouldBe NodeId("Database")
            edge.target.portId shouldBe PortId("conn")
        }

        test("UmlLayoutBridge leaves part-only connectors (no ports) unsplit") {
            val a = UmlComponent(id = "A", name = "A")
            val b = UmlComponent(id = "B", name = "B")
            val connector =
                UmlConnector(
                    id = "conn-free",
                    end1Id = "A",
                    end2Id = "B",
                )
            val diagram = KumlDiagram(name = "Free", elements = listOf(a, b, connector))

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            graph.edges shouldHaveSize 1
            val edge = graph.edges[0]
            edge.source.nodeId shouldBe NodeId("A")
            edge.source.portId.shouldBeNull()
            edge.target.nodeId shouldBe NodeId("B")
            edge.target.portId.shouldBeNull()
        }

        test("UmlLayoutBridge keeps unknown ::-suffixes as raw node IDs (fallback)") {
            // Endpoint references an unknown port — bridge must NOT silently
            // split and produce a bogus port ID. The raw node-style fallback
            // preserves the previous behaviour for unrelated qualified IDs.
            val comp =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::events", name = "events")),
                )
            val other = UmlComponent(id = "Other", name = "Other")
            val connector =
                UmlConnector(
                    id = "conn-bad",
                    end1Id = "OrderService::notAPort",
                    end2Id = "Other",
                )
            val diagram = KumlDiagram(name = "Bad", elements = listOf(comp, other, connector))

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            val edge = graph.edges.single()
            edge.source.nodeId shouldBe NodeId("OrderService::notAPort")
            edge.source.portId.shouldBeNull()
            edge.target.nodeId shouldBe NodeId("Other")
            edge.target.portId.shouldBeNull()
        }
    })
