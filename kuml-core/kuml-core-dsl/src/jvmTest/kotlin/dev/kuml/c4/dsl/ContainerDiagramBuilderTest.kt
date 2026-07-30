package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.ContainerDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ContainerDiagramBuilderTest :
    FunSpec(body = {
        test(name = "container diagram shows all containers of a system") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Container 1")
                            container(name = "Container 2")
                            container(name = "Container 3")
                        }

                    containerDiagram(name = "Containers") {
                        this.system = system
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            // Should include system + 3 containers = 4 elements
            diag.elements shouldHaveSize 4
        }

        test(name = "external systems are optional") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "Main System") {
                            container(name = "Web App")
                            container(name = "API")
                        }
                    val external = softwareSystem(name = "External") { external = true }

                    relationship(source = system, target = external)

                    containerDiagram(name = "Containers") {
                        this.system = system
                        showExternalSystems = true
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            // Should include: Main System (2 containers) + External System = 4 elements
            diag.elements shouldHaveSize 4
        }

        test(name = "exclude containers is supported") {
            lateinit var excluded: C4Container
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "Container 1")
                            excluded = container(name = "Container 2")
                        }

                    containerDiagram(name = "Containers") {
                        this.system = system
                        exclude(excluded)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            val elementNames =
                diag.elements.map { id -> model.elements.find { it.id == id }?.name }
            // System + Container 1 remain; Container 2 was excluded → 2 elements
            diag.elements shouldHaveSize 2
            elementNames shouldContain "Container 1"
            elementNames shouldNotContain "Container 2"
        }

        test(name = "container count includes system plus all its containers") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "API")
                            container(name = "Database")
                            container(name = "Cache")
                        }

                    containerDiagram(name = "Containers") {
                        this.system = system
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            // System (1) + 3 containers = 4 elements
            diag.elements shouldHaveSize 4
        }

        test(name = "multiple external systems are included") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "Main System") {
                            container(name = "API")
                            container(name = "DB")
                        }
                    val external1 = softwareSystem(name = "Email Service") { external = true }
                    val external2 = softwareSystem(name = "Analytics") { external = true }

                    relationship(source = system, target = external1)
                    relationship(source = system, target = external2)

                    containerDiagram(name = "Containers") {
                        this.system = system
                        showExternalSystems = true
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            // Should include: Main System (2 containers) + 2 external systems = 5 elements
            diag.elements shouldHaveSize 5
        }

        test(name = "external systems are excluded when showExternalSystems is false") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "Main System") {
                            container(name = "API")
                            container(name = "DB")
                        }
                    val external = softwareSystem(name = "External") { external = true }

                    relationship(source = system, target = external)

                    containerDiagram(name = "Containers") {
                        this.system = system
                        showExternalSystems = false
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            // Should only include Main System + 2 containers = 3 elements
            diag.elements shouldHaveSize 3
        }

        test(name = "system must be set") {
            var thrown = false
            try {
                c4Model(name = "Test") {
                    softwareSystem(name = "System") {
                        container(name = "Container 1")
                    }

                    containerDiagram(name = "Containers") {
                        // No system set
                    }
                }
            } catch (e: IllegalArgumentException) {
                thrown = true
            }
            thrown shouldBe true
        }

        test(name = "serialization round-trip works") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "API")
                            container(name = "DB")
                        }

                    containerDiagram(name = "Containers") {
                        this.system = system
                    }
                }

            val json = Json.encodeToString(model)
            val decoded = Json.decodeFromString<C4Model>(json)

            val diagrams = decoded.diagrams.filterIsInstance<ContainerDiagram>()
            diagrams shouldHaveSize 1
        }

        test(name = "relationships external to diagram are excluded") {
            val model =
                c4Model(name = "Test") {
                    val system1 =
                        softwareSystem(name = "System 1") {
                            container(name = "API 1")
                        }
                    val system2 =
                        softwareSystem(name = "System 2") {
                            container(name = "API 2")
                        }

                    relationship(source = system1, target = system2)

                    containerDiagram(name = "System 1 Containers") {
                        this.system = system1
                        showExternalSystems = false
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            // Should have no relationships since we're not showing external systems
            // and there are no intra-system relationships
            diag.relationships shouldHaveSize 0
        }

        test(name = "diagram name and description are set correctly") {
            val model =
                c4Model(name = "Test") {
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "API")
                        }

                    containerDiagram(name = "My Container View", description = "This shows the containers") {
                        this.system = system
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            diag.name shouldBe "My Container View"
            diag.description shouldBe "This shows the containers"
        }

        test(name = "related persons are automatically included by default") {
            val model =
                c4Model(name = "Test") {
                    val customer = person(name = "Customer")
                    val system =
                        softwareSystem(name = "Internet Banking") {
                            container(name = "Web App")
                            container(name = "API")
                        }
                    val email = softwareSystem(name = "Email Service") { external = true }

                    relationship(source = customer, target = system)
                    relationship(source = system, target = email)

                    containerDiagram(name = "Containers") {
                        this.system = system
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            val elemNames =
                diag.elements.map { id -> model.elements.find { it.id == id }?.name }
            // System (1) + 2 Container + external Email Service + Customer = 5 Elemente
            diag.elements shouldHaveSize 5
            elemNames shouldContain "Customer"
            elemNames shouldContain "Email Service"
            // Beide Relationships müssen erhalten bleiben — vorher wurde Customer→System
            // herausgefiltert, weil Customer nicht in elements stand.
            diag.relationships shouldHaveSize 2
        }

        test(name = "related persons can be suppressed via showRelatedPersons = false") {
            val model =
                c4Model(name = "Test") {
                    val customer = person(name = "Customer")
                    val system =
                        softwareSystem(name = "Internet Banking") {
                            container(name = "Web App")
                            container(name = "API")
                        }

                    relationship(source = customer, target = system)

                    containerDiagram(name = "Containers") {
                        this.system = system
                        showRelatedPersons = false
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            val elemNames =
                diag.elements.map { id -> model.elements.find { it.id == id }?.name }
            elemNames shouldNotContain "Customer"
            // Ohne Customer im Diagramm wird auch die Relationship Customer→System gefiltert.
            diag.relationships shouldHaveSize 0
        }

        test(name = "unrelated persons are not pulled in") {
            val model =
                c4Model(name = "Test") {
                    person(name = "Lonely Person") // keine Beziehung zum System
                    val system =
                        softwareSystem(name = "System") {
                            container(name = "API")
                        }

                    containerDiagram(name = "Containers") {
                        this.system = system
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            val elemNames =
                diag.elements.map { id -> model.elements.find { it.id == id }?.name }
            elemNames shouldNotContain "Lonely Person"
        }

        test(name = "only includes containers of the target system") {
            val model =
                c4Model(name = "Test") {
                    val system1 =
                        softwareSystem(name = "System 1") {
                            container(name = "API 1")
                            container(name = "DB 1")
                        }
                    val system2 =
                        softwareSystem(name = "System 2") {
                            container(name = "API 2")
                            container(name = "DB 2")
                        }

                    containerDiagram(name = "System 1 Containers") {
                        this.system = system1
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
            val elemNames =
                diag.elements.map { id ->
                    model.elements.find { it.id == id }?.name
                }

            // Should only have System 1's containers
            elemNames shouldContain "System 1"
            elemNames shouldContain "API 1"
            elemNames shouldContain "DB 1"
            elemNames shouldNotContain "System 2"
            elemNames shouldNotContain "API 2"
        }
    })
