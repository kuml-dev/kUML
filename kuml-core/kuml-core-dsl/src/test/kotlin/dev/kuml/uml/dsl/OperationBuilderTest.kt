package dev.kuml.uml.dsl

import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlClass
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class OperationBuilderTest : FunSpec({

    test("operation without parameters uses empty parentheses in id") {
        val cls =
            umlModel("M") {
                classOf("Order") { operation("confirm") }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].id shouldBe "Order::confirm()"
    }

    test("operation default visibility is PUBLIC") {
        val cls =
            umlModel("M") {
                classOf("Order") { operation("confirm") }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].visibility shouldBe Visibility.PUBLIC
    }

    test("operation with one parameter includes type in id") {
        val cls =
            umlModel("M") {
                classOf("Repo") {
                    operation("find") { parameter("id", "Long") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].id shouldBe "Repo::find(Long)"
    }

    test("operation with multiple parameters joins types with comma in id") {
        val cls =
            umlModel("M") {
                classOf("Repo") {
                    operation("findBy") {
                        parameter("name", "String")
                        parameter("active", "Boolean")
                    }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].id shouldBe "Repo::findBy(String,Boolean)"
    }

    test("overloaded operations get distinct ids") {
        val cls =
            umlModel("M") {
                classOf("Svc") {
                    operation("process") {}
                    operation("process") { parameter("input", "String") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        val ids = cls.operations.map { it.id }
        ids[0] shouldBe "Svc::process()"
        ids[1] shouldBe "Svc::process(String)"
        (ids[0] == ids[1]) shouldBe false
    }

    test("operation returns type is stored") {
        val cls =
            umlModel("M") {
                classOf("Repo") {
                    operation("findAll") { returns("List") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].returnType?.name shouldBe "List"
    }

    test("operation isAbstract flag is stored") {
        val cls =
            umlModel("M") {
                classOf("Shape") {
                    operation("area") { isAbstract = true }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].isAbstract shouldBe true
    }

    test("operation isStatic flag is stored") {
        val cls =
            umlModel("M") {
                classOf("Factory") {
                    operation("create") { isStatic = true }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].isStatic shouldBe true
    }

    test("parameter direction defaults to IN") {
        val cls =
            umlModel("M") {
                classOf("Svc") {
                    operation("process") { parameter("input", "String") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].parameters[0].direction shouldBe ParameterDirection.IN
    }

    test("parameter explicit direction is stored") {
        val cls =
            umlModel("M") {
                classOf("Svc") {
                    operation("update") {
                        parameter("result", "String", direction = ParameterDirection.OUT)
                    }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].parameters[0].direction shouldBe ParameterDirection.OUT
    }

    test("operation on interface uses interface id as prefix") {
        val iface =
            umlModel("M") {
                interfaceOf("IRepo") { operation("findAll") }
            }.elements.filterIsInstance<dev.kuml.uml.UmlInterface>().first()
        iface.operations[0].id shouldBe "IRepo::findAll()"
    }

    test("multiple operations accumulate in declaration order") {
        val cls =
            umlModel("M") {
                classOf("Order") {
                    operation("confirm")
                    operation("cancel")
                    operation("ship")
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations shouldHaveSize 3
        cls.operations.map { it.name } shouldBe listOf("confirm", "cancel", "ship")
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
