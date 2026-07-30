package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OclParserTest :
    FunSpec({

        test("parses integer comparison") {
            val tokens = OclLexer.tokenize("self.attributes->size() > 0")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.BinaryOp(
                    op = ">",
                    left =
                        OclExpression.CollectionOp(
                            receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"),
                            op = "size",
                        ),
                    right = OclExpression.IntLit(0),
                )
        }

        test("parses navigation chain") {
            val tokens = OclLexer.tokenize("self.name")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.Navigate(receiver = OclExpression.Self, prop = "name")
        }

        test("parses forAll lambda") {
            val tokens = OclLexer.tokenize("self.attributes->forAll(a | a.name <> 'id')")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"),
                    op = "forAll",
                    bindingVar = "a",
                    body =
                        OclExpression.BinaryOp(
                            op = "<>",
                            left = OclExpression.Navigate(receiver = OclExpression.VarRef("a"), prop = "name"),
                            right = OclExpression.StrLit("id"),
                        ),
                )
        }

        test("parses implies") {
            val tokens = OclLexer.tokenize("self.isAbstract implies self.operations->notEmpty()")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.BinaryOp(
                    op = "implies",
                    left = OclExpression.Navigate(receiver = OclExpression.Self, prop = "isAbstract"),
                    right =
                        OclExpression.CollectionOp(
                            receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "operations"),
                            op = "notEmpty",
                        ),
                )
        }

        test("parses Real literal") {
            val tokens = OclLexer.tokenize("3.14")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.RealLit(3.14)
        }

        test("does not swallow the dot of a navigation as a Real literal") {
            val tokens = OclLexer.tokenize("self.attributes")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes")
        }

        test("parses let/in expression") {
            val tokens = OclLexer.tokenize("let x = 1 in x + 1")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.LetExpr(
                    name = "x",
                    initExpr = OclExpression.IntLit(1),
                    body = OclExpression.BinaryOp(op = "+", left = OclExpression.VarRef("x"), right = OclExpression.IntLit(1)),
                )
        }

        test("parses if/then/else/endif expression") {
            val tokens = OclLexer.tokenize("if self.isAbstract then 1 else 0 endif")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.IfExpr(
                    cond = OclExpression.Navigate(receiver = OclExpression.Self, prop = "isAbstract"),
                    thenExpr = OclExpression.IntLit(1),
                    elseExpr = OclExpression.IntLit(0),
                )
        }

        test("parses nested let inside if") {
            val tokens = OclLexer.tokenize("if true then let x = 1 in x else 0 endif")
            val expr = OclParser(tokens = tokens).parse()
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
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.IterateExpr(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"),
                    iterVar = "a",
                    accVar = "acc",
                    accInit = OclExpression.IntLit(0),
                    body = OclExpression.BinaryOp(op = "+", left = OclExpression.VarRef("acc"), right = OclExpression.IntLit(1)),
                )
        }

        test("parses select with lambda") {
            val tokens = OclLexer.tokenize("self.attributes->select(a | a.isStatic)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"),
                    op = "select",
                    bindingVar = "a",
                    body = OclExpression.Navigate(receiver = OclExpression.VarRef("a"), prop = "isStatic"),
                )
        }

        test("parses collect with lambda") {
            val tokens = OclLexer.tokenize("self.attributes->collect(a | a.name)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"),
                    op = "collect",
                    bindingVar = "a",
                    body = OclExpression.Navigate(receiver = OclExpression.VarRef("a"), prop = "name"),
                )
        }

        test("parses including with argument") {
            val tokens = OclLexer.tokenize("self.attributes->including(self)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"),
                    op = "including",
                    args = listOf(OclExpression.Self),
                )
        }

        test("rejects malformed let without 'in'") {
            val tokens = OclLexer.tokenize("let x = 1 x")
            shouldThrow<OclEvaluationException> {
                OclParser(tokens = tokens).parse()
            }
        }

        test("rejects malformed if without 'endif'") {
            val tokens = OclLexer.tokenize("if true then 1 else 0")
            shouldThrow<OclEvaluationException> {
                OclParser(tokens = tokens).parse()
            }
        }

        // ── Type operations (V3.2.22) ───────────────────────────────────────

        test("parses oclIsTypeOf with a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclIsTypeOf(Order)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.TypeOp(receiver = OclExpression.Self, op = "oclIsTypeOf", typeName = "Order")
        }

        test("parses oclIsKindOf with a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclIsKindOf(Order)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.TypeOp(receiver = OclExpression.Self, op = "oclIsKindOf", typeName = "Order")
        }

        test("parses oclAsType with a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclAsType(Order)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.TypeOp(receiver = OclExpression.Self, op = "oclAsType", typeName = "Order")
        }

        test("parses oclIsUndefined with no arguments") {
            val tokens = OclLexer.tokenize("self.oclIsUndefined()")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.TypeOp(receiver = OclExpression.Self, op = "oclIsUndefined", typeName = null)
        }

        test("parses oclIsInvalid with no arguments") {
            val tokens = OclLexer.tokenize("self.oclIsInvalid()")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.TypeOp(receiver = OclExpression.Self, op = "oclIsInvalid", typeName = null)
        }

        test("parses type operation chained after a navigation") {
            val tokens = OclLexer.tokenize("self.attributes->first().oclIsKindOf(Order)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.TypeOp(
                    receiver =
                        OclExpression.CollectionOp(
                            receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"),
                            op = "first",
                        ),
                    op = "oclIsKindOf",
                    typeName = "Order",
                )
        }

        test("rejects oclIsTypeOf without a type-name argument") {
            val tokens = OclLexer.tokenize("self.oclIsTypeOf()")
            shouldThrow<OclEvaluationException> { OclParser(tokens = tokens).parse() }
        }

        // ── Standard-library operation calls (V3.2.24) ──────────────────────

        test("parses a zero-arg operation call") {
            val tokens = OclLexer.tokenize("self.name.toUpper()")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.OperationCall(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "name"),
                    name = "toUpper",
                )
        }

        test("parses a single-arg operation call") {
            val tokens = OclLexer.tokenize("self.name.concat('!')")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.OperationCall(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "name"),
                    name = "concat",
                    args = listOf(OclExpression.StrLit("!")),
                )
        }

        test("parses a two-arg operation call") {
            val tokens = OclLexer.tokenize("self.name.substring(1, 3)")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.OperationCall(
                    receiver = OclExpression.Navigate(receiver = OclExpression.Self, prop = "name"),
                    name = "substring",
                    args = listOf(OclExpression.IntLit(1), OclExpression.IntLit(3)),
                )
        }

        test("does not treat plain property navigation as an operation call") {
            val tokens = OclLexer.tokenize("self.name")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.Navigate(receiver = OclExpression.Self, prop = "name")
        }

        // ── @pre snapshot (V3.2.22) ──────────────────────────────────────────

        test("parses @pre on a navigation") {
            val tokens = OclLexer.tokenize("self.attributes@pre")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe OclExpression.AtPre(OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes"))
        }

        test("parses @pre chained with a collection op") {
            val tokens = OclLexer.tokenize("self.attributes@pre->size()")
            val expr = OclParser(tokens = tokens).parse()
            expr shouldBe
                OclExpression.CollectionOp(
                    receiver = OclExpression.AtPre(OclExpression.Navigate(receiver = OclExpression.Self, prop = "attributes")),
                    op = "size",
                )
        }

        test("@pre lexer rule requires a word boundary after 'pre' (does not over-match '@preview')") {
            // "@preview" must not be swallowed as AtPre + trailing "view" garbage —
            // the lexer should instead fail on the unexpected '@' character, since
            // "@pre" followed immediately by more letters is not the @pre token.
            shouldThrow<OclEvaluationException> { OclLexer.tokenize("self.attributes@preview") }
        }

        // ── recursion-depth guard (security fix) ─────────────────────────────

        test("rejects deeply nested parens with a clean exception instead of overflowing the stack") {
            // Grammar parens are transparent in the AST (parsePrimary discards the
            // wrapping), so this nesting is invisible to any post-parse AST-depth
            // check — the parser itself must reject it during the recursive
            // descent, before a StackOverflowError has a chance to occur.
            val expr = "(".repeat(2000) + "1" + ")".repeat(2000)
            val tokens = OclLexer.tokenize(expr)
            val ex = shouldThrow<OclEvaluationException> { OclParser(tokens = tokens).parse() }
            ex.message shouldContain "too complex"
        }

        test("rejects a deeply nested 'not' chain with a clean exception instead of overflowing the stack") {
            val expr = "not ".repeat(2000) + "true"
            val tokens = OclLexer.tokenize(expr)
            val ex = shouldThrow<OclEvaluationException> { OclParser(tokens = tokens).parse() }
            ex.message shouldContain "too complex"
        }

        test("rejects a deeply nested unary-minus chain with a clean exception instead of overflowing the stack") {
            val expr = "-".repeat(2000) + "1"
            val tokens = OclLexer.tokenize(expr)
            val ex = shouldThrow<OclEvaluationException> { OclParser(tokens = tokens).parse() }
            ex.message shouldContain "too complex"
        }

        test("accepts a moderately nested unary-minus chain well under the depth cap") {
            // The guard must only reject nesting that actually risks a stack
            // overflow, not ordinary (if unusual) hand-written expressions.
            val expr = "-".repeat(10) + "1"
            val tokens = OclLexer.tokenize(expr)
            OclParser(tokens = tokens).parse() // must not throw
        }
    })
