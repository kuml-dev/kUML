package dev.kuml.uml.dsl

import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PackageBuilderTest : FunSpec({

    test("package builds a UmlPackage") {
        val pkg =
            umlModel(name = "M") { `package`(name = "domain") }
                .elements.filterIsInstance<UmlPackage>().first()
        pkg.name shouldBe "domain"
    }

    test("package id defaults to name at root level") {
        val pkg =
            umlModel(name = "M") { `package`(name = "domain") }
                .elements.filterIsInstance<UmlPackage>().first()
        pkg.id shouldBe "domain"
    }

    test("packageOf alias works as well") {
        val pkg =
            umlModel(name = "M") { packageOf(name = "infra") }
                .elements.filterIsInstance<UmlPackage>().first()
        pkg.name shouldBe "infra"
    }

    test("classOf inside package gets qualified id") {
        val pkg =
            umlModel(name = "M") {
                `package`(name = "domain") {
                    classOf(name = "Order")
                }
            }.elements.filterIsInstance<UmlPackage>().first()
        pkg.members shouldHaveSize 1
        val cls = pkg.members[0]
        cls.shouldBeInstanceOf<UmlClass>()
        cls.id shouldBe "domain::Order"
    }

    test("classOf inside package has name only as name") {
        val pkg =
            umlModel(name = "M") {
                `package`(name = "domain") { classOf(name = "Order") }
            }.elements.filterIsInstance<UmlPackage>().first()
        (pkg.members[0] as UmlClass).name shouldBe "Order"
    }

    test("enumOf inside package gets qualified id") {
        val pkg =
            umlModel(name = "M") {
                `package`(name = "domain") {
                    enumOf(name = "OrderStatus") { literal(name = "DRAFT") }
                }
            }.elements.filterIsInstance<UmlPackage>().first()
        val enum = pkg.members[0] as UmlEnumeration
        enum.id shouldBe "domain::OrderStatus"
        enum.literals[0].id shouldBe "domain::OrderStatus::DRAFT"
    }

    test("nested packages propagate id chain") {
        val root =
            umlModel(name = "M") {
                `package`(name = "com") {
                    `package`(name = "example") {
                        classOf(name = "Order")
                    }
                }
            }.elements.filterIsInstance<UmlPackage>().first()
        val inner = root.members[0] as UmlPackage
        inner.id shouldBe "com::example"
        val cls = inner.members[0] as UmlClass
        cls.id shouldBe "com::example::Order"
    }

    test("multiple classifiers inside a package are all added as members") {
        val pkg =
            umlModel(name = "M") {
                `package`(name = "domain") {
                    classOf(name = "Order")
                    classOf(name = "Customer")
                    enumOf(name = "Status") { literal(name = "A") }
                }
            }.elements.filterIsInstance<UmlPackage>().first()
        pkg.members shouldHaveSize 3
    }

    test("package explicit id override is respected") {
        val pkg =
            umlModel(name = "M") { `package`(name = "domain", id = "my.domain") }
                .elements.filterIsInstance<UmlPackage>().first()
        pkg.id shouldBe "my.domain"
    }

    test("package at root level ends up as element of the diagram") {
        val model = umlModel(name = "M") { `package`(name = "domain") }
        model.elements shouldHaveSize 1
        model.elements[0].shouldBeInstanceOf<UmlPackage>()
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
