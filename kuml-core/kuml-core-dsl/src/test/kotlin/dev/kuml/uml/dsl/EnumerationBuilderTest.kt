package dev.kuml.uml.dsl

import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class EnumerationBuilderTest :
    FunSpec(body = {

        test(name = "enumOf builds a UmlEnumeration") {
            val enum =
                umlModel(name = "M") { enumOf(name = "OrderStatus") }
                    .elements
                    .filterIsInstance<UmlEnumeration>()
                    .first()
            enum.name shouldBe "OrderStatus"
        }

        test(name = "enumOf id defaults to name at root level") {
            val enum =
                umlModel(name = "M") { enumOf(name = "OrderStatus") }
                    .elements
                    .filterIsInstance<UmlEnumeration>()
                    .first()
            enum.id shouldBe "OrderStatus"
        }

        test(name = "enumOf with no literals has empty literal list") {
            val enum =
                umlModel(name = "M") { enumOf(name = "Empty") {} }
                    .elements
                    .filterIsInstance<UmlEnumeration>()
                    .first()
            enum.literals.shouldBeEmpty()
        }

        test(name = "enumOf literal is added with correct name") {
            val enum =
                umlModel(name = "M") {
                    enumOf(name = "Status") { literal(name = "ACTIVE") }
                }.elements.filterIsInstance<UmlEnumeration>().first()
            enum.literals shouldHaveSize 1
            enum.literals[0].name shouldBe "ACTIVE"
        }

        test(name = "enumOf literal id is derived from enum id and literal name") {
            val enum =
                umlModel(name = "M") {
                    enumOf(name = "Status") { literal(name = "ACTIVE") }
                }.elements.filterIsInstance<UmlEnumeration>().first()
            enum.literals[0].id shouldBe "Status::ACTIVE"
        }

        test(name = "enumOf multiple literals accumulate in order") {
            val enum =
                umlModel(name = "M") {
                    enumOf(name = "OrderStatus") {
                        literal(name = "DRAFT")
                        literal(name = "CONFIRMED")
                        literal(name = "SHIPPED")
                        literal(name = "CANCELLED")
                    }
                }.elements.filterIsInstance<UmlEnumeration>().first()
            enum.literals shouldHaveSize 4
            enum.literals.map { it.name } shouldBe listOf("DRAFT", "CONFIRMED", "SHIPPED", "CANCELLED")
        }

        test(name = "enumOf default visibility is PUBLIC") {
            val enum =
                umlModel(name = "M") { enumOf(name = "E") }
                    .elements
                    .filterIsInstance<UmlEnumeration>()
                    .first()
            enum.visibility shouldBe Visibility.PUBLIC
        }

        test(name = "enumOf returned handle has correct id") {
            var handle: UmlEnumeration? = null
            umlModel(name = "M") { handle = enumOf(name = "Status") { literal(name = "A") } }
            handle!!.id shouldBe "Status"
        }

        test(name = "enumOf explicit id override is respected") {
            val enum =
                umlModel(name = "M") { enumOf(name = "Status", id = "pkg::Status") }
                    .elements
                    .filterIsInstance<UmlEnumeration>()
                    .first()
            enum.id shouldBe "pkg::Status"
        }
    })

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
