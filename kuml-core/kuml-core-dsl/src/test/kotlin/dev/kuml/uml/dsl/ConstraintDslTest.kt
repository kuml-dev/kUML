package dev.kuml.uml.dsl

import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements

class ConstraintDslTest :
    FunSpec({

        test("constraint() stores body in UmlClass.constraints") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        constraint("hasAttr", "self.attributes->size() > 0")
                        attribute("id", "UUID")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints shouldHaveSize 1
            cls.constraints[0].name shouldBe "hasAttr"
            cls.constraints[0].body shouldBe "self.attributes->size() > 0"
        }

        test("constraint IDs are disambiguated for duplicate names") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        constraint("check", "self.attributes->size() > 0")
                        constraint("check", "self.operations->notEmpty()")
                    }
                }.elements
                    .filterIsInstance<UmlClass>()
                    .first()

            cls.constraints shouldHaveSize 2
            val ids = cls.constraints.map { it.id }
            ids[0] shouldBe "Order::check"
            ids[1] shouldBe "Order::check~2"
        }
    })
