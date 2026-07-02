package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression
import io.kotest.assertions.throwables.shouldThrow
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

        test("parses Real literal") {
            val tokens = OclLexer.tokenize("3.14")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.RealLit(3.14)
        }

        test("does not swallow the dot of a navigation as a Real literal") {
            val tokens = OclLexer.tokenize("self.attributes")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.Navigate(OclExpression.Self, "attributes")
        }

        test("parses let/in expression") {
            val tokens = OclLexer.tokenize("let x = 1 in x + 1")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.LetExpr(
                    name = "x",
                    initExpr = OclExpression.IntLit(1),
                    body = OclExpression.BinaryOp("+", OclExpression.VarRef("x"), OclExpression.IntLit(1)),
                )
        }

        test("parses if/then/else/endif expression") {
            val tokens = OclLexer.tokenize("if self.isAbstract then 1 else 0 endif")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.IfExpr(
                    cond = OclExpression.Navigate(OclExpression.Self, "isAbstract"),
                    thenExpr = OclExpression.IntLit(1),
                    elseExpr = OclExpression.IntLit(0),
                )
        }

        test("parses nested let inside if") {
            val tokens = OclLexer.tokenize("if true then let x = 1 in x else 0 endif")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.IfExpr(
                    cond = OclExpression.BoolLit(true),
                    thenExpr =
                        OclExpression.LetExpr(
                            name = "x",
                            initExpr = OclExpression.IntLit(1),
                            body = OclExpression.VarRef("x"),
                        ),
                    elseExpr = OclExpression.IntLit(0),
                )
        }

        test("parses iterate with accumulator") {
            val tokens = OclLexer.tokenize("self.attributes->iterate(a; acc = 0 | acc + 1)")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.IterateExpr(
                    receiver = OclExpression.Navigate(OclExpression.Self, "attributes"),
                    iterVar = "a",
                    accVar = "acc",
                    accInit = OclExpression.IntLit(0),
                    body = OclExpression.BinaryOp("+", OclExpression.VarRef("acc"), OclExpression.IntLit(1)),
                )
        }

        test("parses select with lambda") {
            val tokens = OclLexer.tokenize("self.attributes->select(a | a.isStatic)")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(OclExpression.Self, "attributes"),
                    op = "select",
                    bindingVar = "a",
                    body = OclExpression.Navigate(OclExpression.VarRef("a"), "isStatic"),
                )
        }

        test("parses collect with lambda") {
            val tokens = OclLexer.tokenize("self.attributes->collect(a | a.name)")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(OclExpression.Self, "attributes"),
                    op = "collect",
                    bindingVar = "a",
                    body = OclExpression.Navigate(OclExpression.VarRef("a"), "name"),
                )
        }

        test("parses including with argument") {
            val tokens = OclLexer.tokenize("self.attributes->including(self)")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(OclExpression.Self, "attributes"),
                    op = "including",
                    args = listOf(OclExpression.Self),
                )
        }

        test("rejects malformed let without 'in'") {
            val tokens = OclLexer.tokenize("let x = 1 x")
            shouldThrow<OclEvaluationException> {
                OclParser(tokens).parse()
            }
        }

        test("rejects malformed if without 'endif'") {
            val tokens = OclLexer.tokenize("if true then 1 else 0")
            shouldThrow<OclEvaluationException> {
                OclParser(tokens).parse()
            }
        }

        // ── Type operations (V3.2.22) ───────────────────────────────────────

        test("parses oclIsTypeOf with a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclIsTypeOf(Order)")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.TypeOp(OclExpression.Self, "oclIsTypeOf", "Order")
        }

        test("parses oclIsKindOf with a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclIsKindOf(Order)")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.TypeOp(OclExpression.Self, "oclIsKindOf", "Order")
        }

        test("parses oclAsType with a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclAsType(Order)")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.TypeOp(OclExpression.Self, "oclAsType", "Order")
        }

        test("parses oclIsUndefined with no arguments") {
            val tokens = OclLexer.tokenize("self.oclIsUndefined()")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.TypeOp(OclExpression.Self, "oclIsUndefined", null)
        }

        test("parses oclIsInvalid with no arguments") {
            val tokens = OclLexer.tokenize("self.oclIsInvalid()")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.TypeOp(OclExpression.Self, "oclIsInvalid", null)
        }

        test("parses type operation chained after a navigation") {
            val tokens = OclLexer.tokenize("self.attributes->first().oclIsKindOf(Order)")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.TypeOp(
                    receiver =
                        OclExpression.CollectionOp(
                            receiver = OclExpression.Navigate(OclExpression.Self, "attributes"),
                            op = "first",
                        ),
                    op = "oclIsKindOf",
                    typeName = "Order",
                )
        }

        test("rejects oclIsTypeOf without a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclIsTypeOf()")
            shouldThrow<OclEvaluationException> { OclParser(tokens).parse() }
        }

        // ── @pre snapshot (V3.2.22) ──────────────────────────────────────────

        test("parses @pre on a navigation") {
            val tokens = OclLexer.tokenize("self.attributes@pre")
            val expr = OclParser(tokens).parse()
            expr shouldBe OclExpression.AtPre(OclExpression.Navigate(OclExpression.Self, "attributes"))
        }

        test("parses @pre chained with a collection op") {
            val tokens = OclLexer.tokenize("self.attributes@pre->size()")
            val expr = OclParser(tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.AtPre(OclExpression.Navigate(OclExpression.Self, "attributes")),
                    op = "size",
                )
        }

        test("@pre lexer rule requires a word boundary after 'pre' (does not over-match '@preview')") {
            // "@preview" must not be swallowed as AtPre + trailing "view" garbage —
            // the lexer should instead fail on the unexpected '@' character, since
            // "@pre" followed immediately by more letters is not the @pre token.
            shouldThrow<OclEvaluationException> { OclLexer.tokenize("self.attributes@preview") }
        }
    })
