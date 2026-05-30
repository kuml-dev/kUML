package dev.kuml.c4.dsl

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

class ContainerDiagramBuilderTest : FunSpec({
    test("container diagram shows all containers of a system") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container 1")
                        container("Container 2")
                        container("Container 3")
                    }

                containerDiagram("Containers") {
                    this.system = system
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
        // Should include system + 3 containers = 4 elements
        diag.elements shouldHaveSize 4
    }

    test("external systems are optional") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("Main System") {
                        container("Web App")
                        container("API")
                    }
                val external = softwareSystem("External") { external = true }

                relationship(system, external)

                containerDiagram("Containers") {
                    this.system = system
                    showExternalSystems = true
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
        // Should include: Main System (2 containers) + External System = 4 elements
        diag.elements shouldHaveSize 4
    }

    test("exclude containers is supported") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container 1")
                        container("Container 2")
                    }

                containerDiagram("Containers") {
                    this.system = system
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
        // Should contain system + 2 containers = 3 elements
        diag.elements shouldHaveSize 3
    }

    test("container count includes system plus all its containers") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("API")
                        container("Database")
                        container("Cache")
                    }

                containerDiagram("Containers") {
                    this.system = system
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
        // System (1) + 3 containers = 4 elements
        diag.elements shouldHaveSize 4
    }

    test("multiple external systems are included") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("Main System") {
                        container("API")
                        container("DB")
                    }
                val external1 = softwareSystem("Email Service") { external = true }
                val external2 = softwareSystem("Analytics") { external = true }

                relationship(system, external1)
                relationship(system, external2)

                containerDiagram("Containers") {
                    this.system = system
                    showExternalSystems = true
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
        // Should include: Main System (2 containers) + 2 external systems = 5 elements
        diag.elements shouldHaveSize 5
    }

    test("external systems are excluded when showExternalSystems is false") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("Main System") {
                        container("API")
                        container("DB")
                    }
                val external = softwareSystem("External") { external = true }

                relationship(system, external)

                containerDiagram("Containers") {
                    this.system = system
                    showExternalSystems = false
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
        // Should only include Main System + 2 containers = 3 elements
        diag.elements shouldHaveSize 3
    }

    test("system must be set") {
        var thrown = false
        try {
            c4Model("Test") {
                softwareSystem("System") {
                    container("Container 1")
                }

                containerDiagram("Containers") {
                    // No system set
                }
            }
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        thrown shouldBe true
    }

    test("serialization round-trip works") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("API")
                        container("DB")
                    }

                containerDiagram("Containers") {
                    this.system = system
                }
            }

        val json = Json.encodeToString(model)
        val decoded = Json.decodeFromString<C4Model>(json)

        val diagrams = decoded.diagrams.filterIsInstance<ContainerDiagram>()
        diagrams shouldHaveSize 1
    }

    test("relationships external to diagram are excluded") {
        val model =
            c4Model("Test") {
                val system1 =
                    softwareSystem("System 1") {
                        container("API 1")
                    }
                val system2 =
                    softwareSystem("System 2") {
                        container("API 2")
                    }

                relationship(system1, system2)

                containerDiagram("System 1 Containers") {
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

    test("diagram name and description are set correctly") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("API")
                    }

                containerDiagram("My Container View", "This shows the containers") {
                    this.system = system
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ContainerDiagram>()
        diag.name shouldBe "My Container View"
        diag.description shouldBe "This shows the containers"
    }

    test("only includes containers of the target system") {
        val model =
            c4Model("Test") {
                val system1 =
                    softwareSystem("System 1") {
                        container("API 1")
                        container("DB 1")
                    }
                val system2 =
                    softwareSystem("System 2") {
                        container("API 2")
                        container("DB 2")
                    }

                containerDiagram("System 1 Containers") {
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
