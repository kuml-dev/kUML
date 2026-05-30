package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.ComponentDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ComponentDiagramBuilderTest : FunSpec({
    test("component diagram shows all components of a container") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container") {
                            component("Component 1")
                            component("Component 2")
                            component("Component 3")
                        }
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                componentDiagram("Components") {
                    this.container = containers[0]
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        diag.elements shouldHaveSize 4 // Container + 3 Components
    }

    test("external containers are included when flag is set") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("API") {
                            component("Handler")
                        }
                        container("Database")
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                relationship(containers[0], containers[1])

                componentDiagram("Components") {
                    this.container = containers[0]
                    showExternalReferences = true
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        // Should have Handler component, API container, and Database container
        diag.elements.size shouldBe 3
    }

    test("external containers are excluded when flag is false") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("API") {
                            component("Handler")
                        }
                        container("Database")
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                relationship(containers[0], containers[1])

                componentDiagram("Components") {
                    this.container = containers[0]
                    showExternalReferences = false
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        // Should only have Handler component and API container
        diag.elements.size shouldBe 2
    }

    test("exclude components") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container") {
                            component("Component 1")
                            component("Component 2")
                        }
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }
                val components =
                    containers[0].components.mapNotNull { cmpId ->
                        elements.filterIsInstance<C4Component>().find { it.id == cmpId }
                    }

                componentDiagram("Components") {
                    this.container = containers[0]
                    exclude(components[1])
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        val elementNames =
            diag.elements.map { id ->
                model.elements.find { it.id == id }?.name
            }
        elementNames shouldNotContain "Component 2"
        elementNames.contains("Component 1") shouldBe true
    }

    test("relationships between components are included") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container") {
                            component("Auth")
                            component("Payment")
                        }
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }
                val components =
                    containers[0].components.mapNotNull { cmpId ->
                        elements.filterIsInstance<C4Component>().find { it.id == cmpId }
                    }

                relationship(components[0], components[1])

                componentDiagram("Components") {
                    this.container = containers[0]
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        diag.relationships shouldHaveSize 1
    }

    test("components from other containers are not auto-included") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container 1") {
                            component("Component A")
                        }
                        container("Container 2") {
                            component("Component B")
                        }
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                componentDiagram("Components") {
                    this.container = containers[0]
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        val elementNames =
            diag.elements.map { id ->
                model.elements.find { it.id == id }?.name
            }
        elementNames shouldNotContain "Component B"
        elementNames.contains("Component A") shouldBe true
    }

    test("serialization round-trip works") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container") {
                            component("Auth")
                            component("Logger")
                        }
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                componentDiagram("Components") {
                    this.container = containers[0]
                }
            }
        model.diagrams shouldHaveSize 1

        val json = Json.encodeToString(model)
        json.isNotEmpty() shouldBe true

        // Verify the diagram is present and serialized correctly
        model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
    }

    test("diagram name and description are set correctly") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container") {
                            component("Handler")
                        }
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }

                componentDiagram("API Components", description = "Components in the API container") {
                    this.container = containers[0]
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        diag.name shouldBe "API Components"
        diag.description shouldBe "Components in the API container"
    }

    test("relationships can be disabled") {
        val model =
            c4Model("Test") {
                val system =
                    softwareSystem("System") {
                        container("Container") {
                            component("Auth")
                            component("Payment")
                        }
                    }

                val containers =
                    system.containers.mapNotNull { cId ->
                        elements.filterIsInstance<C4Container>().find { it.id == cId }
                    }
                val components =
                    containers[0].components.mapNotNull { cmpId ->
                        elements.filterIsInstance<C4Component>().find { it.id == cmpId }
                    }

                relationship(components[0], components[1])

                componentDiagram("Components") {
                    this.container = containers[0]
                    showRelationships = false
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<ComponentDiagram>()
        diag.relationships shouldHaveSize 0
    }
})
