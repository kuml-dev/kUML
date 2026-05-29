package dev.kuml.uml.dsl

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class TypeBindingTest : FunSpec({

    // ── typeRef() helpers ──────────────────────────────────────────────────────

    test("typeRef with string name sets name and null referencedId") {
        val ref = typeRef("UUID")
        ref.name shouldBe "UUID"
        ref.referencedId.shouldBeNull()
    }

    test("typeRef with classifier handle sets name and referencedId") {
        var cls: dev.kuml.uml.UmlClass? = null
        umlModel("M") { cls = classOf("Order") }
        val ref = typeRef(cls!!)
        ref.name shouldBe "Order"
        ref.referencedId shouldBe "Order"
    }

    test("typeRef with nested classifier carries qualified id as referencedId") {
        var cls: dev.kuml.uml.UmlClass? = null
        umlModel("M") {
            `package`("domain") { cls = classOf("Order") }
        }
        val ref = typeRef(cls!!)
        ref.referencedId shouldBe "domain::Order"
    }

    // ── attribute overloads ────────────────────────────────────────────────────

    test("attribute with String type and attribute with typeRef produce same type name") {
        val model =
            umlModel("M") {
                classOf("Order") {
                    attribute("a", type = "UUID")
                    attribute("b", type = typeRef("UUID"))
                }
            }
        val cls = model.elements.filterIsInstance<UmlClass>().first()
        cls.attributes[0].type.name shouldBe "UUID"
        cls.attributes[1].type.name shouldBe "UUID"
        cls.attributes[0].type shouldBe cls.attributes[1].type
    }

    test("attribute with classifier handle has referencedId set") {
        val model =
            umlModel("M") {
                val status = enumOf("Status") { literal("A") }
                classOf("Order") { attribute("status", type = status) }
            }
        val cls = model.elements.filterIsInstance<UmlClass>().first()
        cls.attributes[0].type.referencedId.shouldNotBeNull()
        cls.attributes[0].type.referencedId shouldBe "Status"
    }

    test("attribute with string type has null referencedId") {
        val model =
            umlModel("M") {
                classOf("Order") { attribute("id", type = "UUID") }
            }
        val cls = model.elements.filterIsInstance<UmlClass>().first()
        cls.attributes[0].type.referencedId.shouldBeNull()
    }

    // ── parseMultiplicity ──────────────────────────────────────────────────────

    test("parseMultiplicity of 1 is 1..1") {
        parseMultiplicity("1") shouldBe Multiplicity(1, 1)
    }

    test("parseMultiplicity of 0..1 is 0..1") {
        parseMultiplicity("0..1") shouldBe Multiplicity(0, 1)
    }

    test("parseMultiplicity of 1..* is 1..null") {
        parseMultiplicity("1..*") shouldBe Multiplicity(1, null)
    }

    test("parseMultiplicity of 0..* is 0..null") {
        parseMultiplicity("0..*") shouldBe Multiplicity(0, null)
    }

    test("parseMultiplicity of * is 0..null") {
        parseMultiplicity("*") shouldBe Multiplicity(0, null)
    }

    test("parseMultiplicity of 2..5 is 2..5") {
        parseMultiplicity("2..5") shouldBe Multiplicity(2, 5)
    }

    test("parseMultiplicity of invalid format throws") {
        shouldThrow<IllegalArgumentException> { parseMultiplicity("bad") }
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
