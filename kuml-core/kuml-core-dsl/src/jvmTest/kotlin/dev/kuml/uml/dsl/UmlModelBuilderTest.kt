package dev.kuml.uml.dsl

import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlModelBuilderTest :
    FunSpec(body = {

        test(name = "umlModel with name produces a KumlModel") {
            val model = umlModel(name = "Order Domain")
            model.name shouldBe "Order Domain"
        }

        test(name = "umlModel language is always UML") {
            val model = umlModel(name = "M")
            model.language shouldBe ModelingLanguage.UML
        }

        test(name = "umlModel default level is PIM") {
            val model = umlModel(name = "M")
            model.level shouldBe ModelLevel.PIM
        }

        test(name = "umlModel respects explicit level") {
            val model = umlModel(name = "M", level = ModelLevel.PSM)
            model.level shouldBe ModelLevel.PSM
        }

        test(name = "umlModel root diagram name equals model name") {
            val model = umlModel(name = "Order Domain")
            (model.root as dev.kuml.core.model.KumlDiagram).name shouldBe "Order Domain"
        }

        test(name = "umlModel with empty block has no elements in root diagram") {
            val model = umlModel(name = "Empty") {}
            model.root.shouldBeInstanceOf<dev.kuml.core.model.KumlDiagram>()
            (model.root as dev.kuml.core.model.KumlDiagram).elements.shouldBeEmpty()
        }

        test(name = "umlModel accumulates classOf elements in the root diagram") {
            val model =
                umlModel(name = "Domain") {
                    classOf(name = "Order")
                    classOf(name = "Customer")
                }
            val diagram = model.root as dev.kuml.core.model.KumlDiagram
            diagram.elements shouldHaveSize 2
        }

        test(name = "umlModel accumulates relationship via association") {
            val model =
                umlModel(name = "Domain") {
                    classOf(name = "Order")
                    classOf(name = "Item")
                    association(sourceId = "Order", targetId = "Item")
                }
            val diagram = model.root as dev.kuml.core.model.KumlDiagram
            diagram.elements shouldHaveSize 3 // 2 classes + 1 association
        }
    })
