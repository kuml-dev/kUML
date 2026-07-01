package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Interaction
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.layout.EdgeId
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

        test("C4LayoutBridge emits one LayoutEdge per C4Interaction in a DynamicDiagram") {
            // Regression guard for the vault feedback in
            // "26 C4 Dynamic – Checkout Flow.md": without this branch the bridge
            // emitted zero edges (the DynamicDiagram's `relationships` list is
            // empty when no static C4Relationships were declared), so the
            // rendered SVG showed only the boxes — no arrows.
            val customer = C4Person(id = "customer", name = "Customer")
            val web = C4SoftwareSystem(id = "web", name = "WebApp")
            val api = C4SoftwareSystem(id = "api", name = "API Server")
            val model =
                C4Model(
                    id = "m",
                    name = "Checkout",
                    elements = listOf(customer, web, api),
                )
            val ix1 = C4Interaction(id = "i1", source = "customer", target = "web", description = "Submit", sequence = 1)
            val ix2 = C4Interaction(id = "i2", source = "web", target = "api", description = "POST", sequence = 2)
            val ix3 = C4Interaction(id = "i3", source = "api", target = "web", description = "201", sequence = 3, response = true)
            val diagram =
                DynamicDiagram(
                    id = "dyn1",
                    name = "Checkout",
                    interactions = listOf(ix1, ix2, ix3),
                    elements = listOf("customer", "web", "api"),
                )

            val graph = C4LayoutBridge.toLayoutGraph(diagram, model)

            graph.nodes shouldHaveSize 3
            graph.edges shouldHaveSize 3
            graph.edges.map { it.id } shouldBe listOf(EdgeId("i1"), EdgeId("i2"), EdgeId("i3"))
            graph.edges[2].source.nodeId shouldBe NodeId("api")
            graph.edges[2].target.nodeId shouldBe NodeId("web")
        }

        test("C4LayoutBridge marks ContainerDiagram boundary as layoutAsCompound with padding") {
            // V11.x — Regressions-Wächter für den C4-Container-Boundary-Fix
            // (siehe Vault-Beispiel [[03 Bereiche/kUML/Beispiele/02 C4 Container – Internet Banking]]).
            //
            // Ohne `layoutAsCompound = true` behandelte ELK die System-Boundary
            // als leeren 0×0-Platzhalter-Knoten. Die Pfeile (Customer→System,
            // System→External) landeten auf diesem unsichtbaren Punkt, während
            // die *gerenderte* Boundary post-layout aus den Bounds der
            // Mitgliedsknoten errechnet wurde — Folge: Pfeile, die ins Nichts
            // führten. Dieser Test stellt sicher, dass beide Eigenschaften
            // (Compound + Padding) gesetzt bleiben, damit ELK die Boundary als
            // echten Compound mit korrekten Bounds platziert.
            val system = C4SoftwareSystem(id = "sys1", name = "Internet Banking")
            val containerA = C4Container(id = "cA", name = "Web App", system = "sys1")
            val containerB = C4Container(id = "cB", name = "API Server", system = "sys1")
            val model =
                C4Model(
                    id = "model",
                    name = "Banking",
                    elements = listOf(system, containerA, containerB),
                )
            val diagram =
                ContainerDiagram(
                    id = "diag1",
                    name = "Container View",
                    system = "sys1",
                    elements = listOf("cA", "cB"),
                )

            val graph = C4LayoutBridge.toLayoutGraph(diagram, model)

            graph.groups shouldHaveSize 1
            val systemGroup = graph.groups[0]
            systemGroup.id shouldBe GroupId("sys1")
            // Kernzusicherung: ELK muss die Boundary als Compound-Node behandeln,
            // sonst landen Edges auf einem 0×0-Platzhalter abseits der gerenderten Box.
            systemGroup.layoutAsCompound shouldBe true
            // Padding != Insets.ZERO sorgt für sichtbaren Atemraum zwischen
            // Container-Knoten und Boundary-Innenkante; ohne Padding schrumpft
            // ELK den Compound auf die Bounding-Box der Kinder zusammen, sodass
            // das gerenderte Boundary-Rechteck flach gegen die Container-Boxen
            // gepresst wird.
            systemGroup.padding shouldBe C4LayoutBridge.C4_BOUNDARY_INSETS
        }

        test("C4LayoutBridge marks ComponentDiagram boundary as layoutAsCompound with padding") {
            // Analog zum Container-Test: die Container-Boundary in einem
            // ComponentDiagram muss aus denselben Gründen als Compound mit
            // Padding angelegt werden — sonst zeigen Pfeile zur/von der
            // Container-Boundary ebenfalls ins Nichts.
            val container = C4Container(id = "cA", name = "API Server")
            val compA = C4Component(id = "compA", name = "Auth Service", container = "cA")
            val compB = C4Component(id = "compB", name = "User Service", container = "cA")
            val model =
                C4Model(
                    id = "model",
                    name = "Banking",
                    elements = listOf(container, compA, compB),
                )
            val diagram =
                ComponentDiagram(
                    id = "diag1",
                    name = "Component View",
                    container = "cA",
                    elements = listOf("compA", "compB"),
                )

            val graph = C4LayoutBridge.toLayoutGraph(diagram, model)

            graph.groups shouldHaveSize 1
            val containerGroup = graph.groups[0]
            containerGroup.id shouldBe GroupId("cA")
            containerGroup.layoutAsCompound shouldBe true
            containerGroup.padding shouldBe C4LayoutBridge.C4_BOUNDARY_INSETS
        }

        test("C4LayoutBridge skips interactions with unresolvable endpoints in a DynamicDiagram") {
            val customer = C4Person(id = "customer", name = "Customer")
            val web = C4SoftwareSystem(id = "web", name = "WebApp")
            val model =
                C4Model(
                    id = "m",
                    name = "Test",
                    elements = listOf(customer, web),
                )
            val good = C4Interaction(id = "i1", source = "customer", target = "web", description = "Submit", sequence = 1)
            val bad = C4Interaction(id = "i2", source = "web", target = "ghost", description = "??", sequence = 2)
            val diagram =
                DynamicDiagram(
                    id = "dyn1",
                    name = "Test",
                    interactions = listOf(good, bad),
                    elements = listOf("customer", "web"),
                )

            val graph = C4LayoutBridge.toLayoutGraph(diagram, model)

            // Only the good interaction makes it into the edge list — the bad
            // one is silently dropped (analogous to the relationship branch).
            graph.edges shouldHaveSize 1
            graph.edges[0].id shouldBe EdgeId("i1")
        }
    })
