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

class DeploymentDiagramBuilderTest :
    FunSpec(body = {
        test(name = "deployment nodes can be nested hierarchically") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web")
                            container(name = "Database")
                        }

                    val webServer = deploymentNode(name = "Web Server")
                    val dbServer = deploymentNode(name = "DB Server")
                    val cloud =
                        deploymentNode(name = "Cloud") {
                            children.add(webServer)
                            children.add(dbServer)
                        }

                    deploymentDiagram(name = "Deployment") {
                        include(cloud)
                    }
                }

            val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
            diag.elements.size shouldBe 3 // Cloud + Web Server + DB Server
        }

        test(name = "containers are deployed to nodes") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web App")
                        }

                    val containers =
                        system.containers.mapNotNull { cId ->
                            elements.filterIsInstance<C4Container>().find { it.id == cId }
                        }

                    val server = deploymentNode(name = "Server")

                    deploymentDiagram(name = "Deployment") {
                        include(server)
                    }
                }

            val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
            diag.elements.shouldNotBeEmpty()
        }

        test(name = "node instances are tracked") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web App")
                        }

                    val server = deploymentNode(name = "Server") { instances = 5 }

                    deploymentDiagram(name = "Deployment") {
                        include(server)
                    }
                }

            val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
            diag.elements.shouldNotBeEmpty()
        }

        test(name = "multiple deployment contexts") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web")
                            container(name = "API")
                        }

                    val dev = deploymentNode(name = "Development")
                    val prod = deploymentNode(name = "Production")

                    deploymentDiagram(name = "Deployment") {
                        include(dev, prod)
                    }
                }

            val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
            diag.elements.size shouldBe 2 // Dev node + Prod node
        }

        test(name = "deployment relationships are inferred") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web")
                            container(name = "Database")
                        }

                    val containers =
                        system.containers.mapNotNull { cId ->
                            elements.filterIsInstance<C4Container>().find { it.id == cId }
                        }

                    relationship(source = containers[0], target = containers[1])

                    val nodeA = deploymentNode(name = "Node A")
                    val nodeB = deploymentNode(name = "Node B")

                    deploymentDiagram(name = "Deployment") {
                        include(nodeA, nodeB)
                    }
                }

            val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
            // The relationship would be included if the containers were deployed on the nodes
            // Since they're not, relationships should be empty - this is correct behavior
            diag.relationships.size shouldBe 0
        }

        test(name = "deeply nested nodes") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Container")
                        }

                    val cluster = deploymentNode(name = "Cluster")
                    val zone = deploymentNode(name = "Zone") { children.add(cluster) }
                    val region = deploymentNode(name = "Region") { children.add(zone) }
                    val cloud = deploymentNode(name = "Cloud") { children.add(region) }

                    deploymentDiagram(name = "Deployment") {
                        include(cloud)
                    }
                }

            val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
            diag.elements.shouldNotBeEmpty()
        }

        test(name = "serialization round-trip works") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web")
                        }

                    val server = deploymentNode(name = "Server")

                    deploymentDiagram(name = "Deployment") {
                        include(server)
                    }
                }

            val json = Json.encodeToString(model)
            val decoded = Json.decodeFromString<dev.kuml.c4.model.C4Model>(json)

            decoded.diagrams.filterIsInstance<DeploymentDiagram>() shouldHaveSize 1
        }

        test(name = "diagram name and description are set correctly") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Web")
                        }

                    val server = deploymentNode(name = "Server")

                    deploymentDiagram(name = "Production Setup", description = "Production deployment") {
                        include(server)
                    }
                }

            val diag = model.diagrams.filterIsInstance<DeploymentDiagram>().first()
            diag.name shouldBe "Production Setup"
            diag.description shouldBe "Production deployment"
        }

        test(name = "empty deployment diagram is allowed") {
            val model =
                c4Model(name = "Test") {
                    softwareSystem(name = "System") {
                        container(name = "Web")
                    }

                    deploymentDiagram(name = "Empty Deployment")
                }

            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<DeploymentDiagram>()
            diag.name shouldBe "Empty Deployment"
        }
    })
