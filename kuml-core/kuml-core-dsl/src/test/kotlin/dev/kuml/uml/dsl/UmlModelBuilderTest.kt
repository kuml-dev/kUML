package dev.kuml.uml.dsl

import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlModelBuilderTest : FunSpec({

    test("umlModel with name produces a KumlModel") {
        val model = umlModel("Order Domain")
        model.name shouldBe "Order Domain"
    }

    test("umlModel language is always UML") {
        val model = umlModel("M")
        model.language shouldBe ModelingLanguage.UML
    }

    test("umlModel default level is PIM") {
        val model = umlModel("M")
        model.level shouldBe ModelLevel.PIM
    }

    test("umlModel respects explicit level") {
        val model = umlModel("M", level = ModelLevel.PSM)
        model.level shouldBe ModelLevel.PSM
    }

    test("umlModel root diagram name equals model name") {
        val model = umlModel("Order Domain")
        (model.root as dev.kuml.core.model.KumlDiagram).name shouldBe "Order Domain"
    }

    test("umlModel with empty block has no elements in root diagram") {
        val model = umlModel("Empty") {}
        model.root.shouldBeInstanceOf<dev.kuml.core.model.KumlDiagram>()
        (model.root as dev.kuml.core.model.KumlDiagram).elements.shouldBeEmpty()
    }

    test("umlModel accumulates classOf elements in the root diagram") {
        val model =
            umlModel("Domain") {
                classOf("Order")
                classOf("Customer")
            }
        val diagram = model.root as dev.kuml.core.model.KumlDiagram
        diagram.elements shouldHaveSize 2
    }

    test("umlModel accumulates relationship via association") {
        val model =
            umlModel("Domain") {
                classOf("Order")
                classOf("Item")
                association(sourceId = "Order", targetId = "Item")
            }
        val diagram = model.root as dev.kuml.core.model.KumlDiagram
        diagram.elements shouldHaveSize 3 // 2 classes + 1 association
    }
})
