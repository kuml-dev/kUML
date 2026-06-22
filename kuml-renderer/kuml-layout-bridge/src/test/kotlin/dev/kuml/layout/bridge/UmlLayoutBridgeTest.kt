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

        // A connector whose source is a nested-part component (OrderRepository, which is
        // a nestedComponent of OrderService) and whose target is an external top-level
        // component (Database) must NOT enter the ELK layout graph. OrderRepository is
        // not an ELK layout node — it is drawn by the SVG renderer inside the OrderService
        // box. Including it in the ELK graph would reference a non-existent node, causing
        // an orphan edge or a layout exception.
        //
        // NOTE: A part-to-external connector (one endpoint in a nested part, the other in
        // a top-level ELK node) is currently UNSUPPORTED — it is silently dropped: ELK
        // does not route it (filtered here) and the SVG renderer does not draw it either
        // (buildInternalConnectorIndex requires BOTH endpoints to fall within the same
        // parent subtree). This is intentional for this iteration; support for cross-
        // boundary connectors (part → external node) is deferred to a future release.
        test("UmlLayoutBridge excludes connector from nested-part component to external node from ELK graph") {
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

            // The connector must NOT appear as a LayoutEdge — OrderRepository is a nested
            // part that never becomes an ELK node. Only the two top-level nodes appear.
            graph.nodes shouldHaveSize 2
            graph.nodes.map { it.id }.toSet() shouldBe setOf(NodeId("OrderService"), NodeId("Database"))
            graph.edges shouldHaveSize 0
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

        // CRITICAL fix: boundary-to-boundary connectors (both endpoint nodeIds resolve
        // to the same composite-structure parent) must NOT become LayoutEdges — the SVG
        // renderer draws them inside the parent box, so ELK routing them as well would
        // cause a double-draw.
        test("UmlLayoutBridge excludes boundary-to-boundary connector from ELK graph (double-draw guard)") {
            val service =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports =
                        listOf(
                            UmlPort(id = "OrderService::api1", name = "api1"),
                            UmlPort(id = "OrderService::api2", name = "api2"),
                        ),
                    nestedComponents = listOf(UmlComponent(id = "Validator", name = "Validator")),
                )
            // Both endpoints resolve to nodeId "OrderService" (the parent component itself).
            val boundaryConnector =
                UmlConnector(
                    id = "conn::boundary",
                    end1Id = "OrderService::api1",
                    end2Id = "OrderService::api2",
                )
            val diagram =
                KumlDiagram(
                    name = "BoundaryTest",
                    elements = listOf(service, boundaryConnector),
                )

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            // The connector must NOT appear as a LayoutEdge — the SVG renderer
            // handles it internally. Only the top-level node is expected.
            graph.nodes shouldHaveSize 1
            graph.edges shouldHaveSize 0
        }

        // MAJOR fix: delegation connectors (boundary-port → nested-part-port, e.g.
        // "OrderService::api" → "Validator::in") must also be excluded from the ELK
        // layout graph. The source nodeId resolves to the top-level parent
        // ("OrderService"), which is NOT in nestedPartIds, but the target nodeId resolves
        // to the nested part ("Validator"), which IS in nestedPartIds. The fix changes
        // the filter from `&&` to `||` so that connectors with EITHER endpoint in a
        // nested-part are excluded. Without this guard the ELK graph would contain a
        // LayoutEdge referencing a node ("Validator") that was never added — causing an
        // orphan edge or a layout exception.
        test("UmlLayoutBridge excludes delegation connector (boundary-to-part) from ELK graph") {
            val validator =
                UmlComponent(
                    id = "Validator",
                    name = "Validator",
                    ports = listOf(UmlPort(id = "Validator::in", name = "in")),
                )
            val service =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::api", name = "api")),
                    nestedComponents = listOf(validator),
                )
            // Delegation connector: boundary port "OrderService::api" (resolves to nodeId
            // "OrderService") → nested-part port "Validator::in" (resolves to nodeId
            // "Validator", which is in nestedPartIds). Must NOT enter ELK graph.
            val delegationConnector =
                UmlConnector(
                    id = "conn::delegation",
                    end1Id = "OrderService::api",
                    end2Id = "Validator::in",
                )
            val diagram =
                KumlDiagram(
                    name = "DelegationTest",
                    elements = listOf(service, delegationConnector),
                )

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            // Only the top-level OrderService node must appear; Validator is a nested part.
            // The delegation connector must NOT appear as a LayoutEdge — the SVG renderer
            // draws it inside the parent's local coordinate frame.
            graph.nodes shouldHaveSize 1
            graph.nodes[0].id shouldBe NodeId("OrderService")
            graph.edges shouldHaveSize 0
        }

        // Regression: a UmlComponent with NO nestedComponents but with a
        // boundary-to-boundary UmlConnector (both endpoint nodeIds resolve to the
        // same flat component) must NOT produce a spurious ELK self-edge.
        //
        // Before the fix, collectCompositeParentIds() only included components with
        // nestedComponents.isNotEmpty(), so a flat component's id was absent from
        // compositeParentIds. The boundary-to-boundary guard therefore failed and the
        // connector fell through to the ELK edge list as a self-loop — a meaningless
        // edge referencing the component as both source and target that could confuse
        // the layout engine.
        //
        // After the fix the guard is: sourceNodeId == targetNodeId (unconditional),
        // so the connector is excluded from ELK regardless of whether the component
        // has nested parts. The SVG renderer's drawComponentBox() also skips drawing
        // internal connectors on flat components (nestedComponents.isNotEmpty() guard),
        // so the connector is cleanly dropped end-to-end with zero kuml-connector lines.
        test("UmlLayoutBridge excludes boundary-to-boundary connector on flat component from ELK graph (no nested parts)") {
            val flatService =
                UmlComponent(
                    id = "FlatService",
                    name = "FlatService",
                    ports =
                        listOf(
                            UmlPort(id = "FlatService::in", name = "in"),
                            UmlPort(id = "FlatService::out", name = "out"),
                        ),
                    // Deliberately NO nestedComponents — this is the flat-component case.
                )
            val boundaryConnector =
                UmlConnector(
                    id = "conn::flat-boundary",
                    end1Id = "FlatService::in",
                    end2Id = "FlatService::out",
                )
            val diagram =
                KumlDiagram(
                    name = "FlatBoundaryTest",
                    elements = listOf(flatService, boundaryConnector),
                )

            val graph = UmlLayoutBridge.toLayoutGraph(diagram)

            // Only the single flat node, zero edges — the connector must NOT become
            // an ELK self-edge (it is unsupported on flat components and silently dropped).
            graph.nodes shouldHaveSize 1
            graph.nodes[0].id shouldBe NodeId("FlatService")
            graph.edges shouldHaveSize 0
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
