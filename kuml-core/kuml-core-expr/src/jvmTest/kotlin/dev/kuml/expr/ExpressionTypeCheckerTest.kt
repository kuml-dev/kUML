package dev.kuml.expr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ExpressionTypeCheckerTest :
    FunSpec({

        fun parse(s: String) = OclLikeExpressionParser.parse(s)

        fun infer(
            s: String,
            env: Map<String, KumlType> = emptyMap(),
        ) = ExpressionTypeChecker.infer(parse(s), env)

        test("1 + 2 → Int") {
            infer("1 + 2") shouldBe KumlType.Int
        }

        test("1.5 + 2.5 → Real") {
            infer("1.5 + 2.5") shouldBe KumlType.Real
        }

        test("true && false → Bool") {
            infer("true && false") shouldBe KumlType.Bool
        }

        test("1 < 2 → Bool") {
            infer("1 < 2") shouldBe KumlType.Bool
        }

        test("1 + \"a\" → TypeError") {
            infer("1 + \"a\"").shouldBeInstanceOf<KumlType.TypeError>()
        }

        test("true + false → TypeError") {
            infer("true + false").shouldBeInstanceOf<KumlType.TypeError>()
        }

        test("AttributeRef with known Int env → Int") {
            infer("x", mapOf("x" to KumlType.Int)) shouldBe KumlType.Int
        }

        test("AttributeRef with unknown name → Unknown") {
            infer("unknownVar") shouldBe KumlType.Unknown
        }

        test("FunctionCall → Unknown (V2.0.20a)") {
            infer("foo(1)") shouldBe KumlType.Unknown
        }

        test("!true → Bool") {
            infer("!true") shouldBe KumlType.Bool
        }

        test("-1 → Int") {
            infer("-1") shouldBe KumlType.Int
        }

        test("'hello' comparison → Bool") {
            infer("\"a\" == \"b\"") shouldBe KumlType.Bool
        }
    })
