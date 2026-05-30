package dev.kuml.uml.dsl

import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlClass
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class OperationBuilderTest : FunSpec(body = {

    test(name = "operation without parameters uses empty parentheses in id") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Order") { operation(name = "confirm") }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].id shouldBe "Order::confirm()"
    }

    test(name = "operation default visibility is PUBLIC") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Order") { operation(name = "confirm") }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].visibility shouldBe Visibility.PUBLIC
    }

    test(name = "operation with one parameter includes type in id") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Repo") {
                    operation(name = "find") { parameter(name = "id", type = "Long") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].id shouldBe "Repo::find(Long)"
    }

    test(name = "operation with multiple parameters joins types with comma in id") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Repo") {
                    operation(name = "findBy") {
                        parameter(name = "name", type = "String")
                        parameter(name = "active", type = "Boolean")
                    }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].id shouldBe "Repo::findBy(String,Boolean)"
    }

    test(name = "overloaded operations get distinct ids") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Svc") {
                    operation(name = "process") {}
                    operation(name = "process") { parameter(name = "input", type = "String") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        val ids = cls.operations.map { it.id }
        ids[0] shouldBe "Svc::process()"
        ids[1] shouldBe "Svc::process(String)"
        (ids[0] == ids[1]) shouldBe false
    }

    test(name = "operation returns type is stored") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Repo") {
                    operation(name = "findAll") { returns(typeName = "List") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].returnType?.name shouldBe "List"
    }

    test(name = "operation isAbstract flag is stored") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Shape") {
                    operation(name = "area") { isAbstract = true }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].isAbstract shouldBe true
    }

    test(name = "operation isStatic flag is stored") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Factory") {
                    operation(name = "create") { isStatic = true }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].isStatic shouldBe true
    }

    test(name = "parameter direction defaults to IN") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Svc") {
                    operation(name = "process") { parameter(name = "input", type = "String") }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].parameters[0].direction shouldBe ParameterDirection.IN
    }

    test(name = "parameter explicit direction is stored") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Svc") {
                    operation(name = "update") {
                        parameter(name = "result", type = "String", direction = ParameterDirection.OUT)
                    }
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations[0].parameters[0].direction shouldBe ParameterDirection.OUT
    }

    test(name = "operation on interface uses interface id as prefix") {
        val iface =
            umlModel(name = "M") {
                interfaceOf(name = "IRepo") { operation(name = "findAll") }
            }.elements.filterIsInstance<dev.kuml.uml.UmlInterface>().first()
        iface.operations[0].id shouldBe "IRepo::findAll()"
    }

    test(name = "multiple operations accumulate in declaration order") {
        val cls =
            umlModel(name = "M") {
                classOf(name = "Order") {
                    operation(name = "confirm")
                    operation(name = "cancel")
                    operation(name = "ship")
                }
            }.elements.filterIsInstance<UmlClass>().first()
        cls.operations shouldHaveSize 3
        cls.operations.map { it.name } shouldBe listOf("confirm", "cancel", "ship")
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
