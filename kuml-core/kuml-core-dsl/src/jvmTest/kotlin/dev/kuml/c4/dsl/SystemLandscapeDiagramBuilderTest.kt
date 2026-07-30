package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.SystemLandscapeDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SystemLandscapeDiagramBuilderTest :
    FunSpec(body = {
        test(name = "includes all systems and persons by default") {
            val model =
                c4Model(name = "Test") {
                    person(name = "P1")
                    person(name = "P2")
                    softwareSystem(name = "S1")
                    softwareSystem(name = "S2")

                    systemLandscapeDiagram(name = "Landscape")
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            // Should include 2 persons + 2 systems = 4 elements
            diag.elements shouldHaveSize 4
        }

        test(name = "can exclude systems") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val s1 = softwareSystem(name = "System 1")
                    val s2 = softwareSystem(name = "System 2")

                    systemLandscapeDiagram(name = "Landscape") {
                        exclude(s2)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            // Verify s1 and p are included, s2 is excluded
            diag.elements shouldHaveSize 2
        }

        test(name = "can exclude persons") {
            val model =
                c4Model(name = "Test") {
                    val p1 = person(name = "Person1")
                    val p2 = person(name = "Person2")
                    val s = softwareSystem(name = "System")

                    systemLandscapeDiagram(name = "Landscape") {
                        exclude(p2)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            // Verify p1 and s are included, p2 is excluded
            diag.elements shouldHaveSize 2
        }

        test(name = "relationships between systems are included") {
            val model =
                c4Model(name = "Test") {
                    val s1 = softwareSystem(name = "S1")
                    val s2 = softwareSystem(name = "S2")
                    relationship(source = s1, target = s2)

                    systemLandscapeDiagram(name = "Landscape")
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            diag.relationships shouldHaveSize 1
        }

        test(name = "relationships between persons and systems are included") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val s = softwareSystem(name = "System")
                    relationship(source = p, target = s)

                    systemLandscapeDiagram(name = "Landscape")
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            diag.relationships shouldHaveSize 1
        }

        test(name = "containers and components are not included") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val s =
                        softwareSystem(name = "System") {
                            container(name = "Container")
                        }

                    systemLandscapeDiagram(name = "Landscape")
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            // Should only include person + system, not container
            diag.elements shouldHaveSize 2
        }

        test(name = "can include specific elements") {
            val model =
                c4Model(name = "Test") {
                    val p1 = person(name = "Person1")
                    val p2 = person(name = "Person2")
                    val s1 = softwareSystem(name = "System 1")
                    val s2 = softwareSystem(name = "System 2")

                    systemLandscapeDiagram(name = "Landscape") {
                        includeAllSystems = false
                        includeAllPersons = false
                        include(p1, s1)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            diag.elements shouldHaveSize 2
        }

        test(name = "excludes elements from relationships when not included") {
            val model =
                c4Model(name = "Test") {
                    val s1 = softwareSystem(name = "S1")
                    val s2 = softwareSystem(name = "S2")
                    val s3 = softwareSystem(name = "S3")
                    relationship(source = s1, target = s2)
                    relationship(source = s2, target = s3)

                    systemLandscapeDiagram(name = "Landscape") {
                        exclude(s3)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            // Should only have s1->s2 relationship, not s2->s3
            diag.relationships shouldHaveSize 1
        }

        test(name = "can disable auto-include of systems") {
            val model =
                c4Model(name = "Test") {
                    val p = person(name = "Person")
                    val s1 = softwareSystem(name = "System 1")
                    val s2 = softwareSystem(name = "System 2")

                    systemLandscapeDiagram(name = "Landscape") {
                        includeAllSystems = false
                        include(s1)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            // Should only have person + explicitly included system
            diag.elements shouldHaveSize 2
        }

        test(name = "can disable auto-include of persons") {
            val model =
                c4Model(name = "Test") {
                    val p1 = person(name = "Person1")
                    val p2 = person(name = "Person2")
                    val s = softwareSystem(name = "System")

                    systemLandscapeDiagram(name = "Landscape") {
                        includeAllPersons = false
                        include(p1)
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            // Should only have explicitly included person + all systems
            diag.elements shouldHaveSize 2
        }

        test(name = "serialization round-trip works") {
            val model =
                c4Model(name = "Test") {
                    person(name = "P")
                    softwareSystem(name = "S")

                    systemLandscapeDiagram(name = "Landscape")
                }

            val json = Json.encodeToString(model)
            val decoded = Json.decodeFromString<C4Model>(json)

            val diagrams = decoded.diagrams.filterIsInstance<SystemLandscapeDiagram>()
            diagrams shouldHaveSize 1
        }

        test(name = "diagram name and description are set correctly") {
            val model =
                c4Model(name = "Test") {
                    person(name = "P")
                    softwareSystem(name = "S")

                    systemLandscapeDiagram(name = "My Landscape", description = "Enterprise overview") {
                        // No custom configuration
                    }
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            diag.name shouldBe "My Landscape"
            diag.description shouldBe "Enterprise overview"
        }

        test(name = "empty model produces empty diagram") {
            val model =
                c4Model(name = "Test") {
                    systemLandscapeDiagram(name = "Empty Landscape")
                }
            model.diagrams shouldHaveSize 1
            val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
            diag.elements.shouldBeEmpty()
            diag.relationships.shouldBeEmpty()
        }
    })
