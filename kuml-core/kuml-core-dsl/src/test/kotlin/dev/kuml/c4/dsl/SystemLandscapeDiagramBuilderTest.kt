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

class SystemLandscapeDiagramBuilderTest : FunSpec({
    test("includes all systems and persons by default") {
        val model = c4Model("Test") {
            person("P1")
            person("P2")
            softwareSystem("S1")
            softwareSystem("S2")

            systemLandscapeDiagram("Landscape")
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        // Should include 2 persons + 2 systems = 4 elements
        diag.elements shouldHaveSize 4
    }

    test("can exclude systems") {
        val model = c4Model("Test") {
            val p = person("Person")
            val s1 = softwareSystem("System 1")
            val s2 = softwareSystem("System 2")

            systemLandscapeDiagram("Landscape") {
                exclude(s2)
            }
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        // Verify s1 and p are included, s2 is excluded
        diag.elements shouldHaveSize 2
    }

    test("can exclude persons") {
        val model = c4Model("Test") {
            val p1 = person("Person1")
            val p2 = person("Person2")
            val s = softwareSystem("System")

            systemLandscapeDiagram("Landscape") {
                exclude(p2)
            }
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        // Verify p1 and s are included, p2 is excluded
        diag.elements shouldHaveSize 2
    }

    test("relationships between systems are included") {
        val model = c4Model("Test") {
            val s1 = softwareSystem("S1")
            val s2 = softwareSystem("S2")
            relationship(s1, s2)

            systemLandscapeDiagram("Landscape")
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        diag.relationships shouldHaveSize 1
    }

    test("relationships between persons and systems are included") {
        val model = c4Model("Test") {
            val p = person("Person")
            val s = softwareSystem("System")
            relationship(p, s)

            systemLandscapeDiagram("Landscape")
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        diag.relationships shouldHaveSize 1
    }

    test("containers and components are not included") {
        val model = c4Model("Test") {
            val p = person("Person")
            val s = softwareSystem("System") {
                container("Container")
            }

            systemLandscapeDiagram("Landscape")
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        // Should only include person + system, not container
        diag.elements shouldHaveSize 2
    }

    test("can include specific elements") {
        val model = c4Model("Test") {
            val p1 = person("Person1")
            val p2 = person("Person2")
            val s1 = softwareSystem("System 1")
            val s2 = softwareSystem("System 2")

            systemLandscapeDiagram("Landscape") {
                includeAllSystems = false
                includeAllPersons = false
                include(p1, s1)
            }
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        diag.elements shouldHaveSize 2
    }

    test("excludes elements from relationships when not included") {
        val model = c4Model("Test") {
            val s1 = softwareSystem("S1")
            val s2 = softwareSystem("S2")
            val s3 = softwareSystem("S3")
            relationship(s1, s2)
            relationship(s2, s3)

            systemLandscapeDiagram("Landscape") {
                exclude(s3)
            }
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        // Should only have s1->s2 relationship, not s2->s3
        diag.relationships shouldHaveSize 1
    }

    test("can disable auto-include of systems") {
        val model = c4Model("Test") {
            val p = person("Person")
            val s1 = softwareSystem("System 1")
            val s2 = softwareSystem("System 2")

            systemLandscapeDiagram("Landscape") {
                includeAllSystems = false
                include(s1)
            }
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        // Should only have person + explicitly included system
        diag.elements shouldHaveSize 2
    }

    test("can disable auto-include of persons") {
        val model = c4Model("Test") {
            val p1 = person("Person1")
            val p2 = person("Person2")
            val s = softwareSystem("System")

            systemLandscapeDiagram("Landscape") {
                includeAllPersons = false
                include(p1)
            }
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        // Should only have explicitly included person + all systems
        diag.elements shouldHaveSize 2
    }

    test("serialization round-trip works") {
        val model = c4Model("Test") {
            person("P")
            softwareSystem("S")

            systemLandscapeDiagram("Landscape")
        }

        val json = Json.encodeToString(model)
        val decoded = Json.decodeFromString<C4Model>(json)

        val diagrams = decoded.diagrams.filterIsInstance<SystemLandscapeDiagram>()
        diagrams shouldHaveSize 1
    }

    test("diagram name and description are set correctly") {
        val model = c4Model("Test") {
            person("P")
            softwareSystem("S")

            systemLandscapeDiagram("My Landscape", "Enterprise overview") {
                // No custom configuration
            }
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        diag.name shouldBe "My Landscape"
        diag.description shouldBe "Enterprise overview"
    }

    test("empty model produces empty diagram") {
        val model = c4Model("Test") {
            systemLandscapeDiagram("Empty Landscape")
        }
        model.diagrams shouldHaveSize 1
        val diag = model.diagrams[0].shouldBeInstanceOf<SystemLandscapeDiagram>()
        diag.elements.shouldBeEmpty()
        diag.relationships.shouldBeEmpty()
    }
})
