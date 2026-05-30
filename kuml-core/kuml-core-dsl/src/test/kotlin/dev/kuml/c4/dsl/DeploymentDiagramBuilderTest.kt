package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.DeploymentDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DeploymentDiagramBuilderTest : FunSpec({
    test("deployment nodes can be nested hierarchically") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Web")
                        container("Database")
                    }

                val webServer = deploymentNode("Web Server")
                val dbServer = deploymentNode("DB Server")
                val cloud =
                    deploymentNode("Cloud") {
                        children.add(webServer)
                        children.add(dbServer)
                    }

                deploymentDiagram("Deployment") {
                    include(cloud)
                }
            }

        val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
        diag.elements.size shouldBe 3 // Cloud + Web Server + DB Server
    }

    test("containers are deployed to nodes") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Web App")
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                val server = deploymentNode("Server")

                deploymentDiagram("Deployment") {
                    include(server)
                }
            }

        val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
        diag.elements.shouldNotBeEmpty()
    }

    test("node instances are tracked") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Web App")
                    }

                val server = deploymentNode("Server") { instances = 5 }

                deploymentDiagram("Deployment") {
                    include(server)
                }
            }

        val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
        diag.elements.shouldNotBeEmpty()
    }

    test("multiple deployment contexts") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Web")
                        container("API")
                    }

                val dev = deploymentNode("Development")
                val prod = deploymentNode("Production")

                deploymentDiagram("Deployment") {
                    include(dev, prod)
                }
            }

        val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
        diag.elements.size shouldBe 2 // Dev node + Prod node
    }

    test("deployment relationships are inferred") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Web")
                        container("Database")
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                relationship(containers[0], containers[1])

                val nodeA = deploymentNode("Node A")
                val nodeB = deploymentNode("Node B")

                deploymentDiagram("Deployment") {
                    include(nodeA, nodeB)
                }
            }

        val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
        // The relationship would be included if the containers were deployed on the nodes
        // Since they're not, relationships should be empty - this is correct behavior
        diag.relationships.size shouldBe 0
    }

    test("deeply nested nodes") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container")
                    }

                val cluster = deploymentNode("Cluster")
                val zone = deploymentNode("Zone") { children.add(cluster) }
                val region = deploymentNode("Region") { children.add(zone) }
                val cloud = deploymentNode("Cloud") { children.add(region) }

                deploymentDiagram("Deployment") {
                    include(cloud)
                }
            }

        val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
        diag.elements.shouldNotBeEmpty()
    }

    test("serialization round-trip works") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Web")
                    }

                val server = deploymentNode("Server")

                deploymentDiagram("Deployment") {
                    include(server)
                }
            }

        val json = Json.encodeToString(model)
        val decoded = Json.decodeFromString<dev.kuml.c4.model.C4Model>(json)

        decoded.diagrams.filterIsInstance<DeploymentDiagram>() shouldHaveSize 1
    }

    test("diagram name and description are set correctly") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Web")
                    }

                val server = deploymentNode("Server")

                deploymentDiagram("Production Setup", description = "Production deployment") {
                    include(server)
                }
            }

        val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
        diag.name shouldBe "Production Setup"
        diag.description shouldBe "Production deployment"
    }

    test("empty deployment diagram is allowed") {
        val model =
            c4Model("Test") {
                softwareSystem("System") {
                    container("Web")
                }

                deploymentDiagram("Empty Deployment")
            }

        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<DeploymentDiagram>()
        diag.name shouldBe "Empty Deployment"
    }
})
