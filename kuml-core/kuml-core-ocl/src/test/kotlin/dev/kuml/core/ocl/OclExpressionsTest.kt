package dev.kuml.core.ocl

import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [OclExpressions.evaluateTracked] (fail-open fix: OCL `<>`
 * against a missing `env` variable).
 *
 * @see OclEvaluatorTest for lower-level [OclEvaluator.referencedMissingVariable] coverage.
 */
class OclExpressionsTest :
    FunSpec({

        val self: Any = UmlClass(id = "Order", name = "Order")

        test("evaluateTracked reports value and referencedMissingVariable identically to plain evaluate for a present variable") {
            val tracked = OclExpressions.evaluateTracked(expression = "x <> 1", self = self, env = mapOf("x" to 2))
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe false
            tracked.value shouldBe OclExpressions.evaluate(expression = "x <> 1", self = self, env = mapOf("x" to 2))
        }

        test("evaluateTracked flags a missing variable behind '<>' even though the raw value is a trusting 'true'") {
            val tracked = OclExpressions.evaluateTracked(expression = "x <> 1", self = self, env = emptyMap())
            tracked.value shouldBe true // the fail-open value on its own, without the flag, would be wrongly trusted
            tracked.referencedMissingVariable shouldBe true
        }

        test("evaluateTracked does not flag self-navigation (self is always bound)") {
            val tracked = OclExpressions.evaluateTracked(expression = "self", self = self, env = emptyMap())
            tracked.referencedMissingVariable shouldBe false
        }

        test("evaluateTracked does not flag a variable that is present and genuinely null") {
            val tracked = OclExpressions.evaluateTracked(expression = "x = null", self = self, env = mapOf("x" to null))
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe false
        }
    })
