package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OclParserTest :
    FunSpec({

        test("parses integer comparison") {
            val tokens = OclLexer.tokenize("self.attributes->size() > 0")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.BinaryOp(
                    op = ">",
                    left =
                        OclExpression.CollectionOp(
                            receiver = OclExpression.Navigate(OclExpression.Self, "attributes"),
                            op = "size",
                        ),
                    right = OclExpression.IntLit(0),
                )
        }

        test("parses navigation chain") {
            val tokens = OclLexer.tokenize("self.name")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.Navigate(OclExpression.Self, "name")
        }

        test("parses forAll lambda") {
            val tokens = OclLexer.tokenize("self.attributes->forAll(a | a.name <> 'id')")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(OclExpression.Self, "attributes"),
                    op = "forAll",
                    bindingVar = "a",
                    body =
                        OclExpression.BinaryOp(
                            op = "<>",
                            left = OclExpression.Navigate(OclExpression.VarRef("a"), "name"),
                            right = OclExpression.StrLit("id"),
                        ),
                )
        }

        test("parses implies") {
            val tokens = OclLexer.tokenize("self.isAbstract implies self.operations->notEmpty()")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.BinaryOp(
                    op = "implies",
                    left = OclExpression.Navigate(OclExpression.Self, "isAbstract"),
                    right =
                        OclExpression.CollectionOp(
                            receiver = OclExpression.Navigate(OclExpression.Self, "operations"),
                            op = "notEmpty",
                        ),
                )
        }
    })
