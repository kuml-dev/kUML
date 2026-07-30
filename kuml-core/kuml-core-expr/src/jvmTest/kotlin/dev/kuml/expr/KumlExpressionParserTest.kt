package dev.kuml.expr

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class KumlExpressionParserTest :
    FunSpec({

        test("true → LiteralBool(true)") {
            OclLikeExpressionParser.parse("true") shouldBe LiteralBool(true)
        }

        test("false → LiteralBool(false)") {
            OclLikeExpressionParser.parse("false") shouldBe LiteralBool(false)
        }

        test("42 → LiteralInt(42)") {
            OclLikeExpressionParser.parse("42") shouldBe LiteralInt(42)
        }

        test("3.14 → LiteralReal(3.14)") {
            OclLikeExpressionParser.parse("3.14") shouldBe LiteralReal(3.14)
        }

        test("double-quoted string → LiteralString") {
            OclLikeExpressionParser.parse("\"hello\"") shouldBe LiteralString("hello")
        }

        test("single-quoted string → LiteralString") {
            OclLikeExpressionParser.parse("'hello'") shouldBe LiteralString("hello")
        }

        test("a.b.c → AttributeRef([a, b, c])") {
            OclLikeExpressionParser.parse("a.b.c") shouldBe AttributeRef(listOf("a", "b", "c"))
        }

        test("foo(1, 2) → FunctionCall(foo, [LiteralInt(1), LiteralInt(2)])") {
            OclLikeExpressionParser.parse("foo(1, 2)") shouldBe
                FunctionCall(name = "foo", args = listOf(LiteralInt(1), LiteralInt(2)))
        }

        test("a && b || c → OR(AND(a, b), c) — AND binds tighter than OR") {
            val result = OclLikeExpressionParser.parse("a && b || c")
            // Expected: (a && b) || c
            result shouldBe
                BinaryOp(
                    op = BinaryOperator.OR,
                    left = BinaryOp(op = BinaryOperator.AND, left = AttributeRef(listOf("a")), right = AttributeRef(listOf("b"))),
                    right = AttributeRef(listOf("c")),
                )
        }

        test("!a → UnaryOp(NOT, AttributeRef)") {
            OclLikeExpressionParser.parse("!a") shouldBe
                UnaryOp(op = UnaryOperator.NOT, operand = AttributeRef(listOf("a")))
        }

        test("-1 → UnaryOp(NEG, LiteralInt(1))") {
            OclLikeExpressionParser.parse("-1") shouldBe
                UnaryOp(op = UnaryOperator.NEG, operand = LiteralInt(1))
        }

        test("a < b - 1 → BinaryOp(LT, a, BinaryOp(SUB, b, 1))") {
            val result = OclLikeExpressionParser.parse("a < b - 1")
            result shouldBe
                BinaryOp(
                    op = BinaryOperator.LT,
                    left = AttributeRef(listOf("a")),
                    right = BinaryOp(op = BinaryOperator.SUB, left = AttributeRef(listOf("b")), right = LiteralInt(1)),
                )
        }

        test("(a + b) * c → MUL(ADD(a, b), c)") {
            val result = OclLikeExpressionParser.parse("(a + b) * c")
            result shouldBe
                BinaryOp(
                    op = BinaryOperator.MUL,
                    left = BinaryOp(op = BinaryOperator.ADD, left = AttributeRef(listOf("a")), right = AttributeRef(listOf("b"))),
                    right = AttributeRef(listOf("c")),
                )
        }

        test("unbalanced '(' → tryParse returns null (no throw)") {
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse(input = "(a + b", errors = errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("unknown token '@foo' → tryParse returns null") {
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse(input = "@foo", errors = errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("empty string → tryParse returns null") {
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse(input = "", errors = errors)
            result shouldBe null
        }

        test("null literal → LiteralNull") {
            OclLikeExpressionParser.parse("null") shouldBe LiteralNull
        }

        test("integer with underscore separator → LiteralInt") {
            OclLikeExpressionParser.parse("1_000") shouldBe LiteralInt(1000)
        }

        test("thermostat guard: event.temperature < event.targetTemperature - 1") {
            val result = OclLikeExpressionParser.parse("event.temperature < event.targetTemperature - 1")
            val op = result.shouldBeInstanceOf<BinaryOp>()
            op.op shouldBe BinaryOperator.LT
            op.left shouldBe AttributeRef(listOf("event", "temperature"))
            op.right shouldBe
                BinaryOp(
                    op = BinaryOperator.SUB,
                    left = AttributeRef(listOf("event", "targetTemperature")),
                    right = LiteralInt(1),
                )
        }

        // ── Nesting-depth safety net (untrusted multi-tenant input) ────────────────
        // Regression coverage for the StackOverflowError DoS: an unbounded amount of
        // nested '(' (or chained '!'/'-') must fail cleanly with a ParseException /
        // null result, never crash the JVM thread with an uncaught StackOverflowError.

        test("20,000 nested parens → parse() throws ParseException, not StackOverflowError") {
            val pathological = "(".repeat(20_000) + "1" + ")".repeat(20_000)
            shouldThrow<ParseException> {
                OclLikeExpressionParser.parse(pathological)
            }
        }

        test("20,000 nested parens → tryParse() returns null, does not throw") {
            val pathological = "(".repeat(20_000) + "1" + ")".repeat(20_000)
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse(input = pathological, errors = errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("20,000 chained '!' → tryParse() returns null, does not throw") {
            val pathological = "!".repeat(20_000) + "true"
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse(input = pathological, errors = errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("20,000 chained unary '-' → tryParse() returns null, does not throw") {
            val pathological = "-".repeat(20_000) + "1"
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse(input = pathological, errors = errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("20,000-deep nested function calls → tryParseEffects() returns null, does not throw") {
            val pathological = "f(".repeat(20_000) + "1" + ")".repeat(20_000)
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParseEffects(input = pathological, errors = errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("nesting just under MAX_NESTING_DEPTH still parses successfully") {
            val depth = OclLikeExpressionParser.MAX_NESTING_DEPTH - 1
            val wellFormed = "(".repeat(depth) + "1" + ")".repeat(depth)
            // Must not throw — depth is within the hard safety cap.
            OclLikeExpressionParser.parse(wellFormed) shouldBe LiteralInt(1)
        }
    })
