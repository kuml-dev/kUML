package dev.kuml.uml.dsl

import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class RelationshipsDslTest :
    FunSpec(body = {

        // ── Generalization ─────────────────────────────────────────────────────────

        test(name = "top-level generalization creates UmlGeneralization") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Dog")
                    classOf(name = "Animal")
                    generalization(specificId = "Dog", generalId = "Animal")
                }
            model.elements.filterIsInstance<UmlGeneralization>() shouldHaveSize 1
        }

        test(name = "generalization id uses gen:: prefix with -|> notation") {
            val model =
                umlModel(name = "M") {
                    generalization(specificId = "Dog", generalId = "Animal")
                }
            model.elements
                .filterIsInstance<UmlGeneralization>()
                .first()
                .id shouldBe "gen::Dog-|>Animal"
        }

        test(name = "generalization stores specific and general ids") {
            val model =
                umlModel(name = "M") {
                    generalization(specificId = "Dog", generalId = "Animal")
                }
            val gen = model.elements.filterIsInstance<UmlGeneralization>().first()
            gen.specificId shouldBe "Dog"
            gen.generalId shouldBe "Animal"
        }

        test(name = "generalization by classifier handles uses handle ids") {
            val model =
                umlModel(name = "M") {
                    val animal = classOf(name = "Animal")
                    val dog = classOf(name = "Dog")
                    generalization(specific = dog, general = animal)
                }
            val gen = model.elements.filterIsInstance<UmlGeneralization>().first()
            gen.specificId shouldBe "Dog"
            gen.generalId shouldBe "Animal"
        }

        // ── Realization ────────────────────────────────────────────────────────────

        test(name = "top-level realization creates UmlInterfaceRealization") {
            val model =
                umlModel(name = "M") {
                    interfaceOf(name = "IOrderSvc")
                    classOf(name = "OrderSvc")
                    realization(implementingId = "OrderSvc", interfaceId = "IOrderSvc")
                }
            model.elements.filterIsInstance<UmlInterfaceRealization>() shouldHaveSize 1
        }

        test(name = "realization id uses real:: prefix with ..|> notation") {
            val model =
                umlModel(name = "M") {
                    realization(implementingId = "OrderSvc", interfaceId = "IOrderSvc")
                }
            model.elements
                .filterIsInstance<UmlInterfaceRealization>()
                .first()
                .id shouldBe
                "real::OrderSvc..|>IOrderSvc"
        }

        test(name = "realization by handles uses handle ids") {
            val model =
                umlModel(name = "M") {
                    val iface = interfaceOf(name = "IRepo")
                    val cls = classOf(name = "OrderRepo")
                    realization(implementing = cls, iface = iface)
                }
            val real = model.elements.filterIsInstance<UmlInterfaceRealization>().first()
            real.implementingId shouldBe "OrderRepo"
            real.interfaceId shouldBe "IRepo"
        }

        // ── Dependency ─────────────────────────────────────────────────────────────

        test(name = "top-level dependency creates UmlDependency") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Order")
                    classOf(name = "OrderStatus")
                    dependency(clientId = "Order", supplierId = "OrderStatus")
                }
            model.elements.filterIsInstance<UmlDependency>() shouldHaveSize 1
        }

        test(name = "dependency id uses dep:: prefix with ..> notation") {
            val model =
                umlModel(name = "M") {
                    dependency(clientId = "Order", supplierId = "OrderStatus")
                }
            model.elements
                .filterIsInstance<UmlDependency>()
                .first()
                .id shouldBe "dep::Order..>OrderStatus"
        }

        test(name = "dependency with name label stores it") {
            val model =
                umlModel(name = "M") {
                    dependency(clientId = "A", supplierId = "B", name = "<<use>>")
                }
            model.elements
                .filterIsInstance<UmlDependency>()
                .first()
                .name shouldBe "<<use>>"
        }

        test(name = "dependency by handles uses handle ids") {
            val model =
                umlModel(name = "M") {
                    val order = classOf(name = "Order")
                    val status = enumOf(name = "OrderStatus") { literal(name = "DRAFT") }
                    dependency(client = order, supplier = status)
                }
            val dep = model.elements.filterIsInstance<UmlDependency>().first()
            dep.clientId shouldBe "Order"
            dep.supplierId shouldBe "OrderStatus"
        }
    })

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
