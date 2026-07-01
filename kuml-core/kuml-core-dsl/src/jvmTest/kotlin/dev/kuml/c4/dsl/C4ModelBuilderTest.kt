package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class C4ModelBuilderTest :
    FunSpec(body = {

        test(name = "metamodel: C4Person can be constructed directly") {
            val person =
                C4Person(
                    id = "p1",
                    name = "Alice",
                    description = "A customer",
                    external = false,
                    location = "Office",
                )
            person.id shouldBe "p1"
            person.name shouldBe "Alice"
            person.description shouldBe "A customer"
            person.external shouldBe false
            person.location shouldBe "Office"
        }

        test(name = "metamodel: C4SoftwareSystem can be constructed directly") {
            val system =
                C4SoftwareSystem(
                    id = "sys1",
                    name = "Banking System",
                    description = "The main banking system",
                    external = false,
                    location = "Cloud",
                    containers = emptyList(),
                )
            system.id shouldBe "sys1"
            system.name shouldBe "Banking System"
            system.containers.shouldBeEmpty()
        }

        test(name = "metamodel: C4Container can be constructed directly") {
            val container =
                C4Container(
                    id = "c1",
                    name = "Web App",
                    description = "Frontend",
                    technology = "React",
                    system = "sys1",
                    components = emptyList(),
                )
            container.id shouldBe "c1"
            container.name shouldBe "Web App"
            container.technology shouldBe "React"
            container.system shouldBe "sys1"
        }

        test(name = "metamodel: C4Component can be constructed directly") {
            val component =
                C4Component(
                    id = "comp1",
                    name = "Auth Service",
                    description = "Handles authentication",
                    technology = "Kotlin",
                    container = "c1",
                )
            component.id shouldBe "comp1"
            component.name shouldBe "Auth Service"
            component.technology shouldBe "Kotlin"
            component.container shouldBe "c1"
        }

        test(name = "metamodel: C4DeploymentNode can be constructed directly") {
            val node =
                C4DeploymentNode(
                    id = "node1",
                    name = "Kubernetes Cluster",
                    description = "Production cluster",
                    technology = "Kubernetes",
                    instances = 3,
                    containerInstances = emptyList(),
                    children = emptyList(),
                )
            node.id shouldBe "node1"
            node.name shouldBe "Kubernetes Cluster"
            node.instances shouldBe 3
        }

        test(name = "metamodel: C4Relationship can be constructed directly") {
            val relationship =
                C4Relationship(
                    id = "rel1",
                    source = "p1",
                    target = "sys1",
                    label = "Uses the system",
                    technology = "HTTPS",
                    bidirectional = false,
                )
            relationship.id shouldBe "rel1"
            relationship.source shouldBe "p1"
            relationship.target shouldBe "sys1"
            relationship.technology shouldBe "HTTPS"
        }

        test(name = "metamodel: C4Model can be constructed directly") {
            val person = C4Person(id = "p1", name = "Alice")
            val system = C4SoftwareSystem(id = "sys1", name = "System A")
            val relationship =
                C4Relationship(
                    id = "rel1",
                    source = "p1",
                    target = "sys1",
                    label = "Uses",
                )
            val model =
                dev.kuml.c4.model.C4Model(
                    id = "model1",
                    name = "Model A",
                    elements = listOf(person, system),
                    relationships = listOf(relationship),
                )
            model.name shouldBe "Model A"
            model.elements.shouldHaveSize(2)
            model.relationships.shouldHaveSize(1)
        }

        test(name = "DSL: empty c4Model builds without error") {
            val model = c4Model(name = "Empty Model")
            model.name shouldBe "Empty Model"
            model.elements.shouldBeEmpty()
            model.relationships.shouldBeEmpty()
        }

        test(name = "DSL: c4Model with person") {
            val model =
                c4Model(name = "With Person") {
                    person(name = "Alice") {
                        description = "A customer"
                        external = true
                        location = "Office"
                    }
                }
            val persons = model.elements.filterIsInstance<C4Person>()
            persons.shouldHaveSize(1)
            persons.first().name shouldBe "Alice"
            persons.first().description shouldBe "A customer"
            persons.first().external shouldBe true
            persons.first().location shouldBe "Office"
        }

        test(name = "DSL: c4Model with softwareSystem") {
            val model =
                c4Model(name = "With System") {
                    softwareSystem(name = "Banking System") {
                        description = "Main banking system"
                        external = false
                    }
                }
            val systems = model.elements.filterIsInstance<C4SoftwareSystem>()
            systems.shouldHaveSize(1)
            systems.first().name shouldBe "Banking System"
            systems.first().description shouldBe "Main banking system"
        }

        test(name = "DSL: softwareSystem contains containers (nesting)") {
            val model =
                c4Model(name = "Nested System") {
                    softwareSystem(name = "Banking System") {
                        container(name = "Web Application") {
                            technology = "React"
                            description = "Frontend"
                        }
                        container(name = "API Server") {
                            technology = "Spring Boot"
                        }
                    }
                }
            val systems = model.elements.filterIsInstance<C4SoftwareSystem>()
            systems.shouldHaveSize(1)
            systems.first().containers.shouldHaveSize(2)

            val containers = model.elements.filterIsInstance<C4Container>()
            containers.shouldHaveSize(2)
            containers.forEach { c ->
                c.system shouldBe systems.first().id
            }
        }

        test(name = "DSL: container contains components (nesting)") {
            val model =
                c4Model(name = "Nested Container") {
                    softwareSystem(name = "System") {
                        container(name = "Web App") {
                            component(name = "Auth Service") {
                                technology = "Spring Security"
                                description = "Handles user authentication"
                            }
                            component(name = "Payment Service") {
                                technology = "Stripe"
                            }
                        }
                    }
                }
            val containers = model.elements.filterIsInstance<C4Container>()
            containers.shouldHaveSize(1)
            containers.first().components.shouldHaveSize(2)

            val components = model.elements.filterIsInstance<C4Component>()
            components.shouldHaveSize(2)
            components.forEach { c ->
                c.container shouldBe containers.first().id
            }
        }

        test(name = "DSL: relationship between person and system") {
            val model =
                c4Model(name = "Relationships") {
                    val customer = person(name = "Customer")
                    val system = softwareSystem(name = "Banking System")
                    relationship(source = customer, target = system) {
                        technology = "HTTPS"
                    }
                }
            val relationships = model.relationships
            relationships.shouldHaveSize(1)
            relationships.first().source shouldBe
                model.elements
                    .filterIsInstance<C4Person>()
                    .first()
                    .id
            relationships.first().target shouldBe
                model.elements
                    .filterIsInstance<C4SoftwareSystem>()
                    .first()
                    .id
            relationships.first().technology shouldBe "HTTPS"
        }

        test(name = "DSL: bidirectional relationship") {
            val model =
                c4Model(name = "Bidirectional") {
                    val system1 = softwareSystem(name = "System A")
                    val system2 = softwareSystem(name = "System B")
                    relationship(source = system1, target = system2) {
                        bidirectional = true
                        technology = "REST API"
                    }
                }
            val relationships = model.relationships
            relationships.shouldHaveSize(1)
            relationships.first().bidirectional shouldBe true
        }

        test(name = "DSL: deploymentNode with nesting") {
            val model =
                c4Model(name = "Deployment") {
                    deploymentNode(name = "Production") {
                        technology = "AWS"
                        instances = 1
                        node(name = "Kubernetes Cluster") {
                            technology = "K8s"
                            instances = 3
                        }
                    }
                }
            val nodes = model.elements.filterIsInstance<C4DeploymentNode>()
            nodes.shouldHaveSize(2)
            val parentNodes = nodes.filter { it.technology == "AWS" }
            parentNodes.shouldHaveSize(1)
            parentNodes.first().children.shouldHaveSize(1)
            parentNodes.first().technology shouldBe "AWS"
        }

        test(name = "DSL: serialization round-trip") {
            val originalModel =
                c4Model(name = "Serializable") {
                    val customer =
                        person(name = "Customer") {
                            description = "End user"
                            external = true
                        }
                    val system =
                        softwareSystem(name = "System") {
                            description = "Main system"
                            container(name = "Web") {
                                technology = "React"
                            }
                        }
                    relationship(source = customer, target = system) {
                        technology = "HTTPS"
                    }
                }

            val json = Json.encodeToString(originalModel)
            val decoded: dev.kuml.c4.model.C4Model = Json.decodeFromString(json)

            decoded.name shouldBe originalModel.name
            decoded.elements.shouldHaveSize(originalModel.elements.size)
            decoded.relationships.shouldHaveSize(originalModel.relationships.size)

            val originalPersons = originalModel.elements.filterIsInstance<C4Person>()
            val decodedPersons = decoded.elements.filterIsInstance<C4Person>()
            decodedPersons.shouldHaveSize(originalPersons.size)
            decodedPersons.first().name shouldBe originalPersons.first().name
        }

        test(name = "DSL: multiple persons have different IDs") {
            val model =
                c4Model(name = "Multiple Persons") {
                    person(name = "Alice")
                    person(name = "Bob")
                    person(name = "Charlie")
                }
            val persons = model.elements.filterIsInstance<C4Person>()
            persons.shouldHaveSize(3)
            val ids = persons.map { it.id }.toSet()
            ids.shouldHaveSize(3)
        }

        test(name = "DSL: multiple elements across different levels") {
            val model =
                c4Model(name = "Complex Model") {
                    // Persons
                    val customer = person(name = "Customer")
                    val admin = person(name = "Admin") { external = true }

                    // Systems
                    val bankingSystem =
                        softwareSystem(name = "Banking System") {
                            container(name = "Web Application") {
                                technology = "Spring Boot"
                                component(name = "Authentication Module")
                                component(name = "Payment Module")
                            }
                            container(name = "API Server") {
                                technology = "Node.js"
                                component(name = "API Gateway")
                            }
                        }

                    val emailSystem =
                        softwareSystem(name = "Email System") {
                            external = true
                        }

                    // Relationships
                    relationship(source = customer, target = bankingSystem) {
                        technology = "HTTPS"
                    }
                    relationship(source = bankingSystem, target = emailSystem) {
                        technology = "SMTP"
                    }

                    // Deployment
                    deploymentNode(name = "Cloud Infrastructure") {
                        technology = "AWS"
                    }
                }

            model.elements.filterIsInstance<C4Person>().shouldHaveSize(2)
            model.elements.filterIsInstance<C4SoftwareSystem>().shouldHaveSize(2)
            model.elements.filterIsInstance<C4Container>().shouldHaveSize(2)
            model.elements.filterIsInstance<C4Component>().shouldHaveSize(3)
            model.elements.filterIsInstance<C4DeploymentNode>().shouldHaveSize(1)
            model.relationships.shouldHaveSize(2)
        }
    })
