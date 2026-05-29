package dev.kuml.uml.dsl

import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class RelationshipsDslTest : FunSpec({

    // ── Generalization ─────────────────────────────────────────────────────────

    test("top-level generalization creates UmlGeneralization") {
        val model =
            umlModel("M") {
                classOf("Dog")
                classOf("Animal")
                generalization(specificId = "Dog", generalId = "Animal")
            }
        model.elements.filterIsInstance<UmlGeneralization>() shouldHaveSize 1
    }

    test("generalization id uses gen:: prefix with -|> notation") {
        val model =
            umlModel("M") {
                generalization(specificId = "Dog", generalId = "Animal")
            }
        model.elements.filterIsInstance<UmlGeneralization>().first().id shouldBe "gen::Dog-|>Animal"
    }

    test("generalization stores specific and general ids") {
        val model =
            umlModel("M") {
                generalization(specificId = "Dog", generalId = "Animal")
            }
        val gen = model.elements.filterIsInstance<UmlGeneralization>().first()
        gen.specificId shouldBe "Dog"
        gen.generalId shouldBe "Animal"
    }

    test("generalization by classifier handles uses handle ids") {
        val model =
            umlModel("M") {
                val animal = classOf("Animal")
                val dog = classOf("Dog")
                generalization(specific = dog, general = animal)
            }
        val gen = model.elements.filterIsInstance<UmlGeneralization>().first()
        gen.specificId shouldBe "Dog"
        gen.generalId shouldBe "Animal"
    }

    // ── Realization ────────────────────────────────────────────────────────────

    test("top-level realization creates UmlInterfaceRealization") {
        val model =
            umlModel("M") {
                interfaceOf("IOrderSvc")
                classOf("OrderSvc")
                realization(implementingId = "OrderSvc", interfaceId = "IOrderSvc")
            }
        model.elements.filterIsInstance<UmlInterfaceRealization>() shouldHaveSize 1
    }

    test("realization id uses real:: prefix with ..|> notation") {
        val model =
            umlModel("M") {
                realization(implementingId = "OrderSvc", interfaceId = "IOrderSvc")
            }
        model.elements.filterIsInstance<UmlInterfaceRealization>().first().id shouldBe
            "real::OrderSvc..|>IOrderSvc"
    }

    test("realization by handles uses handle ids") {
        val model =
            umlModel("M") {
                val iface = interfaceOf("IRepo")
                val cls = classOf("OrderRepo")
                realization(implementing = cls, iface = iface)
            }
        val real = model.elements.filterIsInstance<UmlInterfaceRealization>().first()
        real.implementingId shouldBe "OrderRepo"
        real.interfaceId shouldBe "IRepo"
    }

    // ── Dependency ─────────────────────────────────────────────────────────────

    test("top-level dependency creates UmlDependency") {
        val model =
            umlModel("M") {
                classOf("Order")
                classOf("OrderStatus")
                dependency(clientId = "Order", supplierId = "OrderStatus")
            }
        model.elements.filterIsInstance<UmlDependency>() shouldHaveSize 1
    }

    test("dependency id uses dep:: prefix with ..> notation") {
        val model =
            umlModel("M") {
                dependency(clientId = "Order", supplierId = "OrderStatus")
            }
        model.elements.filterIsInstance<UmlDependency>().first().id shouldBe "dep::Order..>OrderStatus"
    }

    test("dependency with name label stores it") {
        val model =
            umlModel("M") {
                dependency(clientId = "A", supplierId = "B", name = "<<use>>")
            }
        model.elements.filterIsInstance<UmlDependency>().first().name shouldBe "<<use>>"
    }

    test("dependency by handles uses handle ids") {
        val model =
            umlModel("M") {
                val order = classOf("Order")
                val status = enumOf("OrderStatus") { literal("DRAFT") }
                dependency(client = order, supplier = status)
            }
        val dep = model.elements.filterIsInstance<UmlDependency>().first()
        dep.clientId shouldBe "Order"
        dep.supplierId shouldBe "OrderStatus"
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
