package dev.kuml.expr

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OclLikeExpressionParserKeywordTest :
    FunSpec({
        test("and / or / not give the same AST as the symbolic operators") {
            val sym = OclLikeExpressionParser.parse("a && b || !c")
            OclLikeExpressionParser.parse("a and b or not c") shouldBe sym
        }

        test("uppercase words and words after a dot stay identifiers") {
            OclLikeExpressionParser.parse("gate == LogicKind.OR") shouldBe
                BinaryOp(
                    op = BinaryOperator.EQ,
                    left = AttributeRef(listOf("gate")),
                    right = AttributeRef(listOf("LogicKind", "OR")),
                )
            OclLikeExpressionParser.parse("flags.not") shouldBe AttributeRef(listOf("flags", "not"))
            OclLikeExpressionParser.parse("op == Mode.AND") shouldBe
                BinaryOp(op = BinaryOperator.EQ, left = AttributeRef(listOf("op")), right = AttributeRef(listOf("Mode", "AND")))
        }

        test("word operators bind like their symbols") {
            OclLikeExpressionParser.parse("a or b and c") shouldBe OclLikeExpressionParser.parse("a || b && c")
        }

        test("identifiers merely containing keywords stay identifiers") {
            OclLikeExpressionParser.parse("android.notify") shouldBe AttributeRef(listOf("android", "notify"))
            OclLikeExpressionParser.parse("order") shouldBe AttributeRef(listOf("order"))
        }

        test("backtick identifiers allow spaces, umlauts and keywords") {
            OclLikeExpressionParser.parse("`Tür offen` and `and`") shouldBe
                BinaryOp(op = BinaryOperator.AND, left = AttributeRef(listOf("Tür offen")), right = AttributeRef(listOf("and")))
        }

        test("backtick identifier errors carry a column") {
            shouldThrow<ParseException> { OclLikeExpressionParser.parse("`unterminated") }.error.column shouldBe 1
            shouldThrow<ParseException> { OclLikeExpressionParser.parse("x and `a\nb`") }.error.column shouldBe 7
            shouldThrow<ParseException> { OclLikeExpressionParser.parse("``") }
            OclLikeExpressionParser.parse("`" + "x".repeat(64) + "`") shouldBe AttributeRef(listOf("x".repeat(64)))
            shouldThrow<ParseException> { OclLikeExpressionParser.parse("`" + "x".repeat(65) + "`") }
        }
    })
