package dev.kuml.uml.dsl

import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class IdGenerationTest :
    FunSpec(body = {

        test(name = "two identical DSL blocks produce identical ids") {
            fun buildModel() =
                umlModel(name = "M") {
                    `package`(name = "domain") {
                        classOf(name = "Order") {
                            attribute(name = "id", type = "UUID")
                            operation(name = "confirm")
                        }
                    }
                }
            val model1 = buildModel()
            val model2 = buildModel()
            val pkg1 =
                (model1.root as dev.kuml.core.model.KumlDiagram)
                    .elements
                    .filterIsInstance<UmlPackage>()
                    .first()
            val pkg2 =
                (model2.root as dev.kuml.core.model.KumlDiagram)
                    .elements
                    .filterIsInstance<UmlPackage>()
                    .first()
            pkg1.id shouldBe pkg2.id
            (pkg1.members[0] as UmlClass).id shouldBe (pkg2.members[0] as UmlClass).id
        }

        test(name = "deep nesting produces correct qualified id chain") {
            val model =
                umlModel(name = "M") {
                    `package`(name = "com") {
                        `package`(name = "example") {
                            classOf(name = "Order") {
                                attribute(name = "id", type = "UUID")
                                operation(name = "confirm")
                            }
                        }
                    }
                }
            val com =
                (model.root as dev.kuml.core.model.KumlDiagram)
                    .elements
                    .filterIsInstance<UmlPackage>()
                    .first()
            val example = com.members[0] as UmlPackage
            val order = example.members[0] as UmlClass
            com.id shouldBe "com"
            example.id shouldBe "com::example"
            order.id shouldBe "com::example::Order"
            order.attributes[0].id shouldBe "com::example::Order::id"
            order.operations[0].id shouldBe "com::example::Order::confirm()"
        }

        test(name = "operation id with parameters uses types for disambiguation") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Repo") {
                        operation(name = "find") { parameter(name = "id", type = "Long") }
                    }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.operations[0].id shouldBe "Repo::find(Long)"
            cls.operations[0].id shouldContain "Long"
        }

        test(name = "disambiguation appends ~2 for first name collision") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Order")
                    classOf(name = "Order") // duplicate name → should get ~2
                }
            val classes = model.elements.filterIsInstance<UmlClass>()
            classes[0].id shouldBe "Order"
            classes[1].id shouldBe "Order~2"
        }

        test(name = "explicit id override bypasses name-based derivation") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Order", id = "my.company.Order")
                }
            model.elements
                .filterIsInstance<UmlClass>()
                .first()
                .id shouldBe "my.company.Order"
        }

        test(name = "attribute id starts with owner id") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "domain::Order") {
                        attribute(name = "status", type = "String")
                    }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].id shouldStartWith "domain::Order"
        }

        test(name = "generalization id contains both specific and general ids") {
            val model =
                umlModel(name = "M") {
                    generalization(specificId = "Dog", generalId = "Animal")
                }
            val gen = model.elements.filterIsInstance<UmlGeneralization>().first()
            gen.id shouldContain "Dog"
            gen.id shouldContain "Animal"
        }

        test(name = "association id contains source and target ids") {
            val model =
                umlModel(name = "M") {
                    association(sourceId = "Order", targetId = "Item")
                }
            val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
            assoc.id shouldContain "Order"
            assoc.id shouldContain "Item"
        }

        test(name = "enum literal id is derived from enum id") {
            val enum =
                umlModel(name = "M") {
                    enumOf(name = "domain::Status") { literal(name = "DRAFT") }
                }.elements.filterIsInstance<UmlEnumeration>().first()
            enum.literals[0].id shouldStartWith "domain::Status"
        }
    })

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
