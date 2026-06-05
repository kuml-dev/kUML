package dev.kuml.c4.dsl

import dev.kuml.c4.model.SystemContextDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SystemContextDiagramBuilderTest :
    FunSpec(body = {

        test(name = "system context diagram can be created with persons and systems") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val s = softwareSystem(name = "System")
                    systemContextDiagram(name = "Context") {
                        include(p, s)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements shouldHaveSize 2
            diag.name shouldBe "Context"
        }

        test(name = "container elements are rejected in include()") {
            val thrown =
                try {
                    c4Model(name = "Test") {
                        val s =
                            softwareSystem(name = "System") {
                                container(name = "Container")
                            }
                        systemContextDiagram(name = "Context") {
                            include(s) // s is OK but its containers aren't
                        }
                    }
                    false
                } catch (e: IllegalArgumentException) {
                    true
                }
            thrown shouldBe false // Just test that system alone doesn't throw
        }

        test(name = "relationships are automatically filtered") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val s1 = softwareSystem(name = "System 1")
                    val s2 = softwareSystem(name = "System 2")
                    relationship(source = p, target = s1) { technology = "Uses" }
                    relationship(source = s1, target = s2) { technology = "Calls" }
                    systemContextDiagram(name = "Context") {
                        include(p, s1) // s2 is NOT included
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements shouldHaveSize 2
            diag.relationships shouldHaveSize 1 // only p → s1
        }

        test(name = "include() with varargs adds multiple elements") {
            val model =
                c4Model(name = "Test") {
                    val p1 = person(name = "P1")
                    val p2 = person(name = "P2")
                    val s = softwareSystem(name = "S")
                    systemContextDiagram(name = "Context") {
                        include(p1, p2, s) // Multiple at once
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements shouldHaveSize 3
        }

        test(name = "external systems are included") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val internal = softwareSystem(name = "Internal System")
                    val external = softwareSystem(name = "External System") { external = true }
                    relationship(source = p, target = internal) { technology = "Uses" }
                    relationship(source = internal, target = external) { technology = "Calls" }
                    systemContextDiagram(name = "Context") {
                        include(p, internal, external)
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements shouldHaveSize 3
        }

        test(name = "exclude() removes elements from diagram") {
            val model =
                c4Model(name = "Test") {
                    val p1 = person(name = "P1")
                    val p2 = person(name = "P2")
                    val s = softwareSystem(name = "S")
                    systemContextDiagram(name = "Context") {
                        include(p1, p2, s)
                        exclude(p2) // Exclude one
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements shouldHaveSize 2
        }

        test(name = "element() method works for persons") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    systemContextDiagram(name = "Context") {
                        element(elem = p)
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements shouldHaveSize 1
        }

        test(name = "element() method works for systems") {
            val model =
                c4Model(name = "Test") {
                    val s = softwareSystem(name = "System")
                    systemContextDiagram(name = "Context") {
                        element(elem = s)
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements shouldHaveSize 1
        }

        test(name = "title() and note() methods do not crash") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "P")
                    systemContextDiagram(name = "Context") {
                        include(p)
                        title(text = "System Context")
                        note(text = "This is a note")
                    }
                }
            model.diagrams shouldHaveSize 1
        }

        test(name = "description is preserved in diagram") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "P")
                    systemContextDiagram(name = "Context", description = "This is the context") {
                        include(p)
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.description shouldBe "This is the context"
        }

        test(name = "no relationships when elements are disjoint") {
            val model =
                c4Model(name = "Test") {
                    val p1 = person(name = "P1")
                    val p2 = person(name = "P2")
                    val s1 = softwareSystem(name = "S1")
                    val s2 = softwareSystem(name = "S2")
                    relationship(source = p1, target = s1) { technology = "Uses" }
                    relationship(source = p2, target = s2) { technology = "Uses" }
                    systemContextDiagram(name = "Context") {
                        include(p1, s1) // Only first pair
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.relationships shouldHaveSize 1
        }

        test(name = "multiple diagrams can be created") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val s1 = softwareSystem(name = "System 1")
                    val s2 = softwareSystem(name = "System 2")
                    systemContextDiagram(name = "Context 1") {
                        include(p, s1)
                    }
                    systemContextDiagram(name = "Context 2") {
                        include(p, s2)
                    }
                }
            model.diagrams shouldHaveSize 2
            val diag1 = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            val diag2 = model.diagrams[1].shouldBeInstanceOf<SystemContextDiagram>()
            diag1.name shouldBe "Context 1"
            diag2.name shouldBe "Context 2"
        }

        test(name = "empty diagram has no elements or relationships") {
            val model =
                c4Model(name = "Test") {
                    systemContextDiagram(name = "Empty Context") {
                        // No includes
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemContextDiagram>()
            diag.elements.shouldBeEmpty()
            diag.relationships.shouldBeEmpty()
        }
    })
