package dev.kuml.uml.dsl

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.ClassDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlStateMachine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ClassDiagramBuilderTest : FunSpec({

    test("empty class diagram builds without error") {
        val d = classDiagram("Empty") {}
        d.name shouldBe "Empty"
        d.elements.shouldHaveSize(0)
    }

    test("classDiagram contains added class") {
        val d = classDiagram("My Diagram") {
            classOf("Order") {
                attribute("id", type = "UUID")
            }
        }
        val classes = d.elements.filterIsInstance<UmlClass>()
        classes shouldHaveSize 1
        classes.first().name shouldBe "Order"
    }

    test("classDiagram contains interface and generalization") {
        val d = classDiagram("Hierarchy") {
            val animal = classOf("Animal") { isAbstract = true }
            val dog = classOf("Dog") {}
            generalization(specific = dog, general = animal)
        }
        d.elements.filterIsInstance<UmlClass>() shouldHaveSize 2
        val gens = d.elements.filterIsInstance<UmlGeneralization>()
        gens shouldHaveSize 1
        gens.first().specificId shouldBe "Dog"
        gens.first().generalId shouldBe "Animal"
    }

    test("classDiagram with association between two classes") {
        val d = classDiagram("Association") {
            val customer = classOf("Customer") {}
            val order = classOf("Order") {}
            association(source = customer, target = order) {
                source { multiplicity("1") }
                target { multiplicity("0..*") }
            }
        }
        d.elements.filterIsInstance<UmlAssociation>() shouldHaveSize 1
    }

    test("showAttributes false is stored in config") {
        val d = classDiagram("Config Test") {
            showAttributes = false
        }
        val config = d.config
        config.shouldBeInstanceOf<ClassDiagramConfig>()
        config.showAttributes shouldBe false
        config.showOperations shouldBe true
    }

    test("adding state machine to class diagram throws") {
        val sm = UmlStateMachine(id = "OrderSM", name = "OrderSM")
        val builder = ClassDiagramBuilder(name = "Bad")
        shouldThrow<IllegalArgumentException> {
            builder.addNamedElement(sm)
        }
    }

    test("adding include relationship to class diagram throws") {
        val include = UmlInclude(id = "include::A..>B", baseId = "A", additionId = "B")
        val builder = ClassDiagramBuilder(name = "Bad")
        shouldThrow<IllegalArgumentException> {
            builder.addRelationship(include)
        }
    }

    test("diagram type is CLASS") {
        val d = classDiagram("Test") {}
        d.type shouldBe DiagramType.CLASS
    }
})
