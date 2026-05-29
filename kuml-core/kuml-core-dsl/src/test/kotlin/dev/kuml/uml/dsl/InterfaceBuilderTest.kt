package dev.kuml.uml.dsl

import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class InterfaceBuilderTest : FunSpec({

    test("interfaceOf builds a UmlInterface") {
        val iface =
            umlModel("M") { interfaceOf("IOrderSvc") }
                .elements.filterIsInstance<UmlInterface>().first()
        iface.name shouldBe "IOrderSvc"
    }

    test("interfaceOf id defaults to name at root level") {
        val iface =
            umlModel("M") { interfaceOf("IOrderSvc") }
                .elements.filterIsInstance<UmlInterface>().first()
        iface.id shouldBe "IOrderSvc"
    }

    test("interfaceOf default visibility is PUBLIC") {
        val iface =
            umlModel("M") { interfaceOf("IRepo") }
                .elements.filterIsInstance<UmlInterface>().first()
        iface.visibility shouldBe Visibility.PUBLIC
    }

    test("interfaceOf has no attributes by default") {
        val iface =
            umlModel("M") { interfaceOf("IRepo") }
                .elements.filterIsInstance<UmlInterface>().first()
        iface.attributes.size shouldBe 0
    }

    test("interfaceOf operation is added with correct id") {
        val iface =
            umlModel("M") {
                interfaceOf("IRepo") {
                    operation("findAll")
                }
            }.elements.filterIsInstance<UmlInterface>().first()
        iface.operations shouldHaveSize 1
        iface.operations[0].id shouldBe "IRepo::findAll()"
    }

    test("interfaceOf extends creates UmlGeneralization in diagram") {
        val model =
            umlModel("M") {
                val base = interfaceOf("IBase")
                interfaceOf("IExtended") { extends(base) }
            }
        val gens = model.elements.filterIsInstance<UmlGeneralization>()
        gens shouldHaveSize 1
        gens[0].specificId shouldBe "IExtended"
        gens[0].generalId shouldBe "IBase"
    }

    test("interfaceOf returned handle has correct id") {
        var handle: UmlInterface? = null
        umlModel("M") { handle = interfaceOf("ISvc") }
        handle!!.id shouldBe "ISvc"
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
