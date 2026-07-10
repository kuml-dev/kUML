package dev.kuml.core.ocl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class OclSyntaxTest :
    FunSpec({

        context("highlight") {
            test("classifies a navigation + comparison expression") {
                OclSyntax.highlight("self.count > 3") shouldBe
                    listOf(
                        HighlightToken(0, 4, OclTokenKind.KEYWORD), // self
                        HighlightToken(4, 5, OclTokenKind.OPERATOR), // .
                        HighlightToken(5, 10, OclTokenKind.IDENT), // count
                        HighlightToken(11, 12, OclTokenKind.OPERATOR), // >
                        HighlightToken(13, 14, OclTokenKind.LITERAL), // 3
                    )
            }

            test("colors control-flow keywords, not the bound identifier") {
                val tokens = OclSyntax.highlight("if x then 1 else 2 endif")
                tokens.count { it.kind == OclTokenKind.KEYWORD } shouldBe 4
                tokens.first { it.start == 3 && it.end == 4 }.kind shouldBe OclTokenKind.IDENT // x
            }

            test("colors true/false/null/int/real/string as LITERAL") {
                OclSyntax.highlight("true") shouldBe listOf(HighlightToken(0, 4, OclTokenKind.LITERAL))
                OclSyntax.highlight("false") shouldBe listOf(HighlightToken(0, 5, OclTokenKind.LITERAL))
                OclSyntax.highlight("null") shouldBe listOf(HighlightToken(0, 4, OclTokenKind.LITERAL))
                OclSyntax.highlight("3.14") shouldBe listOf(HighlightToken(0, 4, OclTokenKind.LITERAL))
                // String literal span includes the surrounding quotes.
                OclSyntax.highlight("'abc'") shouldBe listOf(HighlightToken(0, 5, OclTokenKind.LITERAL))
            }

            test("colors parens as PAREN") {
                OclSyntax.highlight("(a)") shouldBe
                    listOf(
                        HighlightToken(0, 1, OclTokenKind.PAREN),
                        HighlightToken(1, 2, OclTokenKind.IDENT),
                        HighlightToken(2, 3, OclTokenKind.PAREN),
                    )
            }

            test("colors arrow + collection op name + parens") {
                OclSyntax.highlight("c->size()") shouldBe
                    listOf(
                        HighlightToken(0, 1, OclTokenKind.IDENT), // c
                        HighlightToken(1, 3, OclTokenKind.OPERATOR), // ->
                        HighlightToken(3, 7, OclTokenKind.IDENT), // size (navigated op name, not a keyword)
                        HighlightToken(7, 8, OclTokenKind.PAREN),
                        HighlightToken(8, 9, OclTokenKind.PAREN),
                    )
            }

            test("colors @pre as KEYWORD") {
                OclSyntax.highlight("x@pre") shouldBe
                    listOf(
                        HighlightToken(0, 1, OclTokenKind.IDENT),
                        HighlightToken(1, 5, OclTokenKind.KEYWORD),
                    )
            }

            test("empty and whitespace-only input highlight to an empty list") {
                OclSyntax.highlight("") shouldBe emptyList()
                OclSyntax.highlight("   ") shouldBe emptyList()
            }

            test("tolerates an unexpected character without throwing, and keeps highlighting after it") {
                OclSyntax.highlight("a # b") shouldBe
                    listOf(
                        HighlightToken(0, 1, OclTokenKind.IDENT),
                        HighlightToken(2, 3, OclTokenKind.ERROR),
                        HighlightToken(4, 5, OclTokenKind.IDENT),
                    )
            }

            test("tolerates an unterminated string literal without throwing") {
                OclSyntax.highlight("'unterminated") shouldBe
                    listOf(HighlightToken(0, 13, OclTokenKind.ERROR))
            }
        }

        context("typeCheck") {
            test("accepts a valid navigation comparison") {
                OclSyntax.typeCheck(
                    "self.count > 3",
                    OclScope(mapOf("count" to OclType.INTEGER)),
                ) shouldBe OclCheckResult.Ok
            }

            test("accepts a valid scope-variable comparison") {
                OclSyntax.typeCheck(
                    "event > 1",
                    OclScope(mapOf("event" to OclType.INTEGER)),
                ) shouldBe OclCheckResult.Ok
            }

            test("reports a syntax error with a non-null range") {
                val result = OclSyntax.typeCheck("self.", OclScope(emptyMap()))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.range.shouldNotBeNull()
                error.message.shouldNotBeNull()
            }

            test("reports a syntax error for a dangling operator") {
                OclSyntax.typeCheck("1 +", OclScope(emptyMap())).shouldBeInstanceOf<OclCheckResult.Error>()
            }

            test("reports an unknown variable, with a range covering it") {
                val result = OclSyntax.typeCheck("foo > 1", OclScope(emptyMap()))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.message shouldContain "foo"
                error.range.shouldNotBeNull()
            }

            test("reports an unknown variable even when a known variable is referenced first") {
                val result = OclSyntax.typeCheck("a > b", OclScope(mapOf("a" to OclType.INTEGER)))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.message shouldContain "b"
                error.range.shouldNotBeNull()
            }

            test("never flags self as an unknown variable") {
                OclSyntax.typeCheck("self.x", OclScope(emptyMap())) shouldBe OclCheckResult.Ok
            }

            test("does not flag a forAll lambda's bound variable") {
                OclSyntax.typeCheck(
                    "coll->forAll(x | x > 0)",
                    OclScope(mapOf("coll" to OclType.COLLECTION)),
                ) shouldBe OclCheckResult.Ok
            }

            test("does not flag a let-bound variable") {
                OclSyntax.typeCheck("let y = 1 in y > 0", OclScope(emptyMap())) shouldBe OclCheckResult.Ok
            }

            test("rejects an expression longer than the size guard") {
                val expr = "1 " + "+ 1 ".repeat(2000)
                val result = OclSyntax.typeCheck(expr, OclScope(emptyMap()))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.message shouldContain "too long"
            }

            test("rejects an expression nested deeper than the AST depth cap") {
                // Grammar parens are transparent (parsePrimary discards them), so
                // depth must come from a construct that actually nests in the AST —
                // a chain of unary minuses does, one UnaryOp per '-'.
                val expr = "-".repeat(80) + "1"
                val result = OclSyntax.typeCheck(expr, OclScope(emptyMap()))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.message shouldContain "too complex"
            }

            test("rejects deeply nested parens without overflowing the stack (never throws)") {
                // Grammar parens are transparent in the AST (parsePrimary discards
                // the wrapping), so this nesting produces NO extra AST depth and
                // is invisible to the post-parse nodeCount/depth guard — only a
                // parse-time recursion-depth guard can catch it. Stays comfortably
                // under MAX_EXPRESSION_LENGTH (4096 chars) so the length guard
                // does not mask what this test is actually checking.
                val expr = "(".repeat(2000) + "1" + ")".repeat(2000)
                val result = OclSyntax.typeCheck(expr, OclScope(emptyMap()))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.message shouldContain "too complex"
            }

            test("rejects a deeply nested 'not' chain without overflowing the stack (never throws)") {
                // "not " is 4 chars; keep the total under MAX_EXPRESSION_LENGTH
                // (4096) so the length guard doesn't mask the recursion guard.
                val expr = "not ".repeat(1000) + "true"
                val result = OclSyntax.typeCheck(expr, OclScope(emptyMap()))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.message shouldContain "too complex"
            }

            test("flags a sound literal type mismatch") {
                val result = OclSyntax.typeCheck("'a' > 3", OclScope(emptyMap()))
                val error = result.shouldBeInstanceOf<OclCheckResult.Error>()
                error.message shouldContain "mismatch"
            }

            test("does not false-positive a type mismatch on an unresolved navigation") {
                val result = OclSyntax.typeCheck("self.a > 3", OclScope(emptyMap()))
                if (result is OclCheckResult.Error) {
                    result.message shouldNotContain "mismatch"
                }
            }

            test("is deterministic for the same input") {
                val first = OclSyntax.typeCheck("self.count > 3", OclScope(mapOf("count" to OclType.INTEGER)))
                val second = OclSyntax.typeCheck("self.count > 3", OclScope(mapOf("count" to OclType.INTEGER)))
                first shouldBe second
            }
        }
    })
