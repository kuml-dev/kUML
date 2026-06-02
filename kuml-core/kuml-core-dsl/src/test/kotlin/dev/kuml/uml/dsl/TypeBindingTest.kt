package dev.kuml.uml.dsl

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class TypeBindingTest :
    FunSpec(body = {

        // ── typeRef() helpers ──────────────────────────────────────────────────────

        test(name = "typeRef with string name sets name and null referencedId") {
            val ref = typeRef("UUID")
            ref.name shouldBe "UUID"
            ref.referencedId.shouldBeNull()
        }

        test(name = "typeRef with classifier handle sets name and referencedId") {
            var cls: dev.kuml.uml.UmlClass? = null
            umlModel(name = "M") { cls = classOf(name = "Order") }
            val ref = typeRef(cls!!)
            ref.name shouldBe "Order"
            ref.referencedId shouldBe "Order"
        }

        test(name = "typeRef with nested classifier carries qualified id as referencedId") {
            var cls: dev.kuml.uml.UmlClass? = null
            umlModel(name = "M") {
                `package`(name = "domain") { cls = classOf(name = "Order") }
            }
            val ref = typeRef(cls!!)
            ref.referencedId shouldBe "domain::Order"
        }

        // ── attribute overloads ────────────────────────────────────────────────────

        test(name = "attribute with String type and attribute with typeRef produce same type name") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        attribute(name = "a", type = "UUID")
                        attribute(name = "b", type = typeRef(name = "UUID"))
                    }
                }
            val cls = model.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].type.name shouldBe "UUID"
            cls.attributes[1].type.name shouldBe "UUID"
            cls.attributes[0].type shouldBe cls.attributes[1].type
        }

        test(name = "attribute with classifier handle has referencedId set") {
            val model =
                umlModel(name = "M") {
                    val status = enumOf(name = "Status") { literal(name = "A") }
                    classOf(name = "Order") { attribute(name = "status", type = status) }
                }
            val cls = model.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0]
                .type.referencedId
                .shouldNotBeNull()
            cls.attributes[0].type.referencedId shouldBe "Status"
        }

        test(name = "attribute with string type has null referencedId") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Order") { attribute(name = "id", type = "UUID") }
                }
            val cls = model.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0]
                .type.referencedId
                .shouldBeNull()
        }

        // ── parseMultiplicity ──────────────────────────────────────────────────────

        test(name = "parseMultiplicity of 1 is 1..1") {
            parseMultiplicity(spec = "1") shouldBe Multiplicity(lower = 1, upper = 1)
        }

        test(name = "parseMultiplicity of 0..1 is 0..1") {
            parseMultiplicity(spec = "0..1") shouldBe Multiplicity(lower = 0, upper = 1)
        }

        test(name = "parseMultiplicity of 1..* is 1..null") {
            parseMultiplicity(spec = "1..*") shouldBe Multiplicity(lower = 1, upper = null)
        }

        test(name = "parseMultiplicity of 0..* is 0..null") {
            parseMultiplicity(spec = "0..*") shouldBe Multiplicity(lower = 0, upper = null)
        }

        test(name = "parseMultiplicity of * is 0..null") {
            parseMultiplicity(spec = "*") shouldBe Multiplicity(lower = 0, upper = null)
        }

        test(name = "parseMultiplicity of 2..5 is 2..5") {
            parseMultiplicity(spec = "2..5") shouldBe Multiplicity(lower = 2, upper = 5)
        }

        test(name = "parseMultiplicity of invalid format throws") {
            shouldThrow<IllegalArgumentException> { parseMultiplicity(spec = "bad") }
        }
    })

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
