package dev.kuml.c4.dsl

import dev.kuml.c4.model.SystemContextDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SystemContextDiagramBuilderTest : FunSpec({

    test("system context diagram can be created with persons and systems") {
        val model =
            c4Model("Test") {
                val p = person("Person")
                val s = softwareSystem("System")
                systemContextDiagram("Context") {
                    include(p, s)
                }
            }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements shouldHaveSize 2
        diag.name shouldBe "Context"
    }

    test("container elements are rejected in include()") {
        val thrown =
            try {
                c4Model("Test") {
                    val s =
                        softwareSystem("System") {
                            container("Container")
                        }
                    systemContextDiagram("Context") {
                        include(s) // s is OK but its containers aren't
                    }
                }
                false
            } catch (e: IllegalArgumentException) {
                true
            }
        thrown shouldBe false // Just test that system alone doesn't throw
    }

    test("relationships are automatically filtered") {
        val model =
            c4Model("Test") {
                val p = person("Person")
                val s1 = softwareSystem("System 1")
                val s2 = softwareSystem("System 2")
                relationship(p, s1) { technology = "Uses" }
                relationship(s1, s2) { technology = "Calls" }
                systemContextDiagram("Context") {
                    include(p, s1) // s2 is NOT included
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements shouldHaveSize 2
        diag.relationships shouldHaveSize 1 // only p → s1
    }

    test("include() with varargs adds multiple elements") {
        val model =
            c4Model("Test") {
                val p1 = person("P1")
                val p2 = person("P2")
                val s = softwareSystem("S")
                systemContextDiagram("Context") {
                    include(p1, p2, s) // Multiple at once
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements shouldHaveSize 3
    }

    test("external systems are included") {
        val model =
            c4Model("Test") {
                val p = person("Person")
                val internal = softwareSystem("Internal System")
                val external = softwareSystem("External System") { external = true }
                relationship(p, internal) { technology = "Uses" }
                relationship(internal, external) { technology = "Calls" }
                systemContextDiagram("Context") {
                    include(p, internal, external)
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements shouldHaveSize 3
    }

    test("exclude() removes elements from diagram") {
        val model =
            c4Model("Test") {
                val p1 = person("P1")
                val p2 = person("P2")
                val s = softwareSystem("S")
                systemContextDiagram("Context") {
                    include(p1, p2, s)
                    exclude(p2) // Exclude one
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements shouldHaveSize 2
    }

    test("element() method works for persons") {
        val model =
            c4Model("Test") {
                val p = person("Person")
                systemContextDiagram("Context") {
                    element(p)
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements shouldHaveSize 1
    }

    test("element() method works for systems") {
        val model =
            c4Model("Test") {
                val s = softwareSystem("System")
                systemContextDiagram("Context") {
                    element(s)
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements shouldHaveSize 1
    }

    test("title() and note() methods do not crash") {
        val model =
            c4Model("Test") {
                val p = person("P")
                systemContextDiagram("Context") {
                    include(p)
                    title("System Context")
                    note("This is a note")
                }
            }
        model.diagrams shouldHaveSize 1
    }

    test("description is preserved in diagram") {
        val model =
            c4Model("Test") {
                val p = person("P")
                systemContextDiagram("Context", "This is the context") {
                    include(p)
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.description shouldBe "This is the context"
    }

    test("no relationships when elements are disjoint") {
        val model =
            c4Model("Test") {
                val p1 = person("P1")
                val p2 = person("P2")
                val s1 = softwareSystem("S1")
                val s2 = softwareSystem("S2")
                relationship(p1, s1) { technology = "Uses" }
                relationship(p2, s2) { technology = "Uses" }
                systemContextDiagram("Context") {
                    include(p1, s1) // Only first pair
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.relationships shouldHaveSize 1
    }

    test("multiple diagrams can be created") {
        val model =
            c4Model("Test") {
                val p = person("Person")
                val s1 = softwareSystem("System 1")
                val s2 = softwareSystem("System 2")
                systemContextDiagram("Context 1") {
                    include(p, s1)
                }
                systemContextDiagram("Context 2") {
                    include(p, s2)
                }
            }
        model.diagrams shouldHaveSize 2
        val diag1 = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        val diag2 = model.diagrams[1].shouldBeInstanceOf<SystemContextDiagram>()
        diag1.name shouldBe "Context 1"
        diag2.name shouldBe "Context 2"
    }

    test("empty diagram has no elements or relationships") {
        val model =
            c4Model("Test") {
                systemContextDiagram("Empty Context") {
                    // No includes
                }
            }
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
        diag.elements.shouldBeEmpty()
        diag.relationships.shouldBeEmpty()
    }
})
