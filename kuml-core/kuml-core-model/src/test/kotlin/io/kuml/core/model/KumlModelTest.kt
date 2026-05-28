package io.kuml.core.model

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class KumlModelTest : FunSpec({

    test("empty model has no diagrams") {
        val model = KumlModel()
        model.diagrams.shouldBeEmpty()
    }

    test("model holds diagrams correctly") {
        val diagram = KumlDiagram(name = "My Diagram", type = DiagramType.CLASS)
        val model = KumlModel(diagrams = listOf(diagram))
        model.diagrams shouldHaveSize 1
        model.diagrams.first().name shouldBe "My Diagram"
    }

    test("KumlDiagram defaults to CLASS type") {
        val diagram = KumlDiagram(name = "Default")
        diagram.type shouldBe DiagramType.CLASS
    }

    test("KumlDiagram elements are empty by default") {
        val diagram = KumlDiagram(name = "Empty")
        diagram.elements.shouldBeEmpty()
    }

    test("KumlModel and KumlDiagram implement KumlElement") {
        withClue("KumlModel should be a KumlElement") {
            val model: KumlElement = KumlModel()
            model shouldBe KumlModel()
        }
        withClue("KumlDiagram should be a KumlElement") {
            val diagram: KumlElement = KumlDiagram(name = "Test")
            diagram shouldBe KumlDiagram(name = "Test")
        }
    }
})
