package dev.kuml.core.ocl

import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OclEvaluatorTest :
    FunSpec({

        test("evaluates size comparison to true") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "Order::id",
                                name = "id",
                                type = UmlTypeRef("UUID"),
                            ),
                        ),
                )
            val tokens = OclLexer.tokenize("self.attributes->size() > 0")
            val expr = OclParser(tokens).parse()
            val result = OclEvaluator(cls).eval(expr)
            result shouldBe true
        }

        test("evaluates forAll") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(id = "Order::id", name = "id", type = UmlTypeRef("UUID")),
                            UmlProperty(id = "Order::name", name = "name", type = UmlTypeRef("String")),
                        ),
                )
            val tokens = OclLexer.tokenize("self.attributes->forAll(a | a.name <> 'status')")
            val expr = OclParser(tokens).parse()
            val result = OclEvaluator(cls).eval(expr)
            result shouldBe true
        }

        test("evaluates implies") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    isAbstract = true,
                    operations =
                        listOf(
                            UmlOperation(id = "Order::confirm", name = "confirm"),
                        ),
                )
            val tokens = OclLexer.tokenize("self.isAbstract implies self.operations->notEmpty()")
            val expr = OclParser(tokens).parse()
            val result = OclEvaluator(cls).eval(expr)
            result shouldBe true
        }
    })
