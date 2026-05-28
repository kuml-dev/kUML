package io.kuml.core.dsl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kuml.core.model.DiagramType

class DiagramBuilderTest : FunSpec({

    test("diagram with name builds a KumlDiagram") {
        val d = diagram(name = "My Diagram")
        d.name shouldBe "My Diagram"
    }

    test("diagram defaults to CLASS type") {
        val d = diagram(name = "Default")
        d.type shouldBe DiagramType.CLASS
    }

    test("diagram respects explicit type") {
        val d = diagram(name = "Sequence", type = DiagramType.SEQUENCE)
        d.type shouldBe DiagramType.SEQUENCE
    }

    test("diagram elements are empty by default") {
        val d = diagram(name = "Empty") {}
        d.elements.shouldBeEmpty()
    }

    test("diagram with empty block is equivalent to diagram without block") {
        val withBlock = diagram(name = "Test") {}
        val withoutBlock = diagram(name = "Test")
        withBlock shouldBe withoutBlock
    }

    test("named parameters are used correctly") {
        val d =
            diagram(
                name = "Named Params Test",
                type = DiagramType.USE_CASE,
            )
        d.name shouldBe "Named Params Test"
        d.type shouldBe DiagramType.USE_CASE
    }
})
