package dev.kuml.expr

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExpressionEvaluatorTest :
    FunSpec({

        fun eval(
            s: String,
            context: Map<String, Any?> = emptyMap(),
        ) = ExpressionEvaluator.evaluate(OclLikeExpressionParser.parse(s), context)

        test("1 + 2 = 3L") {
            eval("1 + 2") shouldBe 3L
        }

        test("true && false = false") {
            eval("true && false") shouldBe false
        }

        test("true || false = true") {
            eval("true || false") shouldBe true
        }

        test("a < b with context {a=1, b=2} = true") {
            eval("a < b", mapOf("a" to 1L, "b" to 2L)) shouldBe true
        }

        test("a < b with context {a=2, b=1} = false") {
            eval("a < b", mapOf("a" to 2L, "b" to 1L)) shouldBe false
        }

        test("a < b with context {a=1, b=2} using ints") {
            eval("a < b", mapOf("a" to 1, "b" to 2)) shouldBe true
        }

        test("single-segment AttributeRef resolved from context") {
            eval("x", mapOf("x" to 42L)) shouldBe 42L
        }

        test("multi-segment a.b with Map context navigates into nested map") {
            val context = mapOf("a" to mapOf("b" to 99L))
            eval("a.b", context) shouldBe 99L
        }

        test("a.b missing → null (unknown path)") {
            eval("a.b", mapOf("a" to "notAMap")) shouldBe null
        }

        test("\"foo\" + 1 throws EvaluationException") {
            shouldThrow<EvaluationException> {
                eval("\"foo\" + 1")
            }
        }

        test("!true = false") {
            eval("!true") shouldBe false
        }

        test("-(3) = -3L") {
            eval("-3") shouldBe -3L
        }

        test("null literal evaluates to null") {
            eval("null") shouldBe null
        }

        test("thermostat guard: event.temperature < event.targetTemperature - 1 with context") {
            // Simulate how OclGuardEvaluator provides context: "event" → map of payload fields
            val context =
                mapOf(
                    "event" to mapOf("temperature" to 16L, "targetTemperature" to 21L),
                )
            // 16 < 21 - 1 = 16 < 20 = true
            eval("event.temperature < event.targetTemperature - 1", context) shouldBe true
        }
    })
