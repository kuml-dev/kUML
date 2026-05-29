package dev.kuml.uml.dsl

import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class EnumerationBuilderTest : FunSpec({

    test("enumOf builds a UmlEnumeration") {
        val enum =
            umlModel("M") { enumOf("OrderStatus") }
                .elements.filterIsInstance<UmlEnumeration>().first()
        enum.name shouldBe "OrderStatus"
    }

    test("enumOf id defaults to name at root level") {
        val enum =
            umlModel("M") { enumOf("OrderStatus") }
                .elements.filterIsInstance<UmlEnumeration>().first()
        enum.id shouldBe "OrderStatus"
    }

    test("enumOf with no literals has empty literal list") {
        val enum =
            umlModel("M") { enumOf("Empty") {} }
                .elements.filterIsInstance<UmlEnumeration>().first()
        enum.literals.shouldBeEmpty()
    }

    test("enumOf literal is added with correct name") {
        val enum =
            umlModel("M") {
                enumOf("Status") { literal("ACTIVE") }
            }.elements.filterIsInstance<UmlEnumeration>().first()
        enum.literals shouldHaveSize 1
        enum.literals[0].name shouldBe "ACTIVE"
    }

    test("enumOf literal id is derived from enum id and literal name") {
        val enum =
            umlModel("M") {
                enumOf("Status") { literal("ACTIVE") }
            }.elements.filterIsInstance<UmlEnumeration>().first()
        enum.literals[0].id shouldBe "Status::ACTIVE"
    }

    test("enumOf multiple literals accumulate in order") {
        val enum =
            umlModel("M") {
                enumOf("OrderStatus") {
                    literal("DRAFT")
                    literal("CONFIRMED")
                    literal("SHIPPED")
                    literal("CANCELLED")
                }
            }.elements.filterIsInstance<UmlEnumeration>().first()
        enum.literals shouldHaveSize 4
        enum.literals.map { it.name } shouldBe listOf("DRAFT", "CONFIRMED", "SHIPPED", "CANCELLED")
    }

    test("enumOf default visibility is PUBLIC") {
        val enum =
            umlModel("M") { enumOf("E") }
                .elements.filterIsInstance<UmlEnumeration>().first()
        enum.visibility shouldBe Visibility.PUBLIC
    }

    test("enumOf returned handle has correct id") {
        var handle: UmlEnumeration? = null
        umlModel("M") { handle = enumOf("Status") { literal("A") } }
        handle!!.id shouldBe "Status"
    }

    test("enumOf explicit id override is respected") {
        val enum =
            umlModel("M") { enumOf("Status", id = "pkg::Status") }
                .elements.filterIsInstance<UmlEnumeration>().first()
        enum.id shouldBe "pkg::Status"
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
