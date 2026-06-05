package dev.kuml.uml.dsl

import dev.kuml.core.dsl.diagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DslScopeIsolationTest :
    FunSpec(body = {

        test(name = "diagram scope has UML builders available") {
            val d =
                diagram(name = "Class Diagram", type = DiagramType.CLASS) {
                    classOf("Order")
                    classOf("Item")
                }
            d.elements shouldHaveSize 2
            d.elements[0].shouldBeInstanceOf<UmlClass>()
        }

        test(name = "umlModel scope can build full order domain model") {
            val model =
                umlModel("Order Domain") {
                    val status =
                        enumOf("OrderStatus") {
                            literal("DRAFT")
                            literal("CONFIRMED")
                            literal("SHIPPED")
                            literal("CANCELLED")
                        }

                    `package`("domain") {
                        classOf("Order") {
                            attribute("id", type = "UUID")
                            attribute("status", type = status)
                            operation("confirm")
                            operation("cancel")
                        }
                        classOf("OrderItem") {
                            attribute("quantity", type = "Int")
                            attribute("unitPrice", type = "BigDecimal")
                        }
                        classOf("Customer") {
                            attribute("id", type = "UUID")
                            attribute("email", type = "String")
                        }
                    }

                    association(sourceId = "domain::Order", targetId = "domain::OrderItem") {
                        name = "contains"
                        aggregation = AggregationKind.COMPOSITE
                        source { multiplicity("1") }
                        target { multiplicity("1..*") }
                    }

                    association(sourceId = "domain::Customer", targetId = "domain::Order") {
                        source { multiplicity("1") }
                        target { multiplicity("0..*") }
                    }

                    dependency(clientId = "domain::Order", supplierId = "OrderStatus")
                }

            val diagram = model.root as KumlDiagram
            val pkg = diagram.elements.filterIsInstance<UmlPackage>().first()
            pkg.members shouldHaveSize 3

            val order = pkg.members.filterIsInstance<UmlClass>().first { it.name == "Order" }
            order.id shouldBe "domain::Order"
            order.attributes shouldHaveSize 2
            order.operations shouldHaveSize 2

            val statusEnum = diagram.elements.filterIsInstance<UmlEnumeration>().first()
            statusEnum.literals shouldHaveSize 4

            val associations = diagram.elements.filterIsInstance<UmlAssociation>()
            associations shouldHaveSize 2
        }

        test(name = "diagram scope also supports classOf and associations") {
            val d =
                diagram(name = "Inheritance", type = DiagramType.CLASS) {
                    val animal = classOf("Animal") { isAbstract = true }
                    val dog = classOf("Dog") { extends(animal) }
                    val cat = classOf("Cat") { extends("Animal") }
                    generalization(specificId = "Fish", generalId = "Animal")
                }
            // 3 classes + 2 inline extends + 1 explicit generalization = 6 elements
            d.elements shouldHaveSize 6
            d.elements.filterIsInstance<UmlGeneralization>() shouldHaveSize 3
        }

        test(name = "package scope does not expose association builder (compile-time safety)") {
            // This test documents the intended scope restriction:
            // Calling association() inside `package { }` is not possible because
            // PackageBuilder : UmlContainerScope (not UmlModelScope).
            // The following code would NOT compile:
            //   diagram("D") { `package`("p") { association(...) { ... } } }
            // We verify the runtime separation by ensuring a package cannot hold UmlAssociation.
            val model =
                umlModel("M") {
                    `package`("domain") {
                        classOf("Order")
                    }
                    association(sourceId = "domain::Order", targetId = "domain::Order")
                }
            val pkg = (model.root as KumlDiagram).elements.filterIsInstance<UmlPackage>().first()
            pkg.members.filterIsInstance<UmlAssociation>() shouldHaveSize 0

            val topLevelAssocs = (model.root as KumlDiagram).elements.filterIsInstance<UmlAssociation>()
            topLevelAssocs shouldHaveSize 1
        }
    })
