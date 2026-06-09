package dev.kuml.expr

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
                FunctionCall("foo", listOf(LiteralInt(1), LiteralInt(2)))
        }

        test("a && b || c → OR(AND(a, b), c) — AND binds tighter than OR") {
            val result = OclLikeExpressionParser.parse("a && b || c")
            // Expected: (a && b) || c
            result shouldBe
                BinaryOp(
                    BinaryOperator.OR,
                    BinaryOp(BinaryOperator.AND, AttributeRef(listOf("a")), AttributeRef(listOf("b"))),
                    AttributeRef(listOf("c")),
                )
        }

        test("!a → UnaryOp(NOT, AttributeRef)") {
            OclLikeExpressionParser.parse("!a") shouldBe
                UnaryOp(UnaryOperator.NOT, AttributeRef(listOf("a")))
        }

        test("-1 → UnaryOp(NEG, LiteralInt(1))") {
            OclLikeExpressionParser.parse("-1") shouldBe
                UnaryOp(UnaryOperator.NEG, LiteralInt(1))
        }

        test("a < b - 1 → BinaryOp(LT, a, BinaryOp(SUB, b, 1))") {
            val result = OclLikeExpressionParser.parse("a < b - 1")
            result shouldBe
                BinaryOp(
                    BinaryOperator.LT,
                    AttributeRef(listOf("a")),
                    BinaryOp(BinaryOperator.SUB, AttributeRef(listOf("b")), LiteralInt(1)),
                )
        }

        test("(a + b) * c → MUL(ADD(a, b), c)") {
            val result = OclLikeExpressionParser.parse("(a + b) * c")
            result shouldBe
                BinaryOp(
                    BinaryOperator.MUL,
                    BinaryOp(BinaryOperator.ADD, AttributeRef(listOf("a")), AttributeRef(listOf("b"))),
                    AttributeRef(listOf("c")),
                )
        }

        test("unbalanced '(' → tryParse returns null (no throw)") {
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse("(a + b", errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("unknown token '@foo' → tryParse returns null") {
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse("@foo", errors)
            result shouldBe null
            errors.isNotEmpty() shouldBe true
        }

        test("empty string → tryParse returns null") {
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParse("", errors)
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
                    BinaryOperator.SUB,
                    AttributeRef(listOf("event", "targetTemperature")),
                    LiteralInt(1),
                )
        }
    })
