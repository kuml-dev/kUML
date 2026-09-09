package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [TokenFlowGuardEvaluator] — previously no dedicated test file
 * existed for this class (only integration coverage via
 * [TokenFlowParityTest]). These tests pin the delegation contract directly:
 * [TokenFlowGuardEvaluator] must forward to whatever [GuardEvaluator] it is
 * given (defaulting to [dev.kuml.runtime.activity.ActivityGuardEvaluator]),
 * so the `!`-negation bugfix on the delegate reaches [TokenFlowEngine] guards
 * automatically, without any code change in this class.
 */
class TokenFlowGuardEvaluatorTest :
    FunSpec({

        fun instance(vars: Map<String, Any?> = emptyMap()) = TokenFlowEvalContext(vars)

        test("delegates '!'-negation to ActivityGuardEvaluator") {
            val ev = TokenFlowGuardEvaluator()
            ev.evaluate(guard = "!allow", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "!allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("honours an injected delegate") {
            val forceFalse = GuardEvaluator { _, _, _ -> GuardResult.False }
            val ev = TokenFlowGuardEvaluator(delegate = forceFalse)

            // "allow" would evaluate to True against the default delegate given
            // this instance's variables — proving the injected delegate, not the
            // default ActivityGuardEvaluator, actually decided the result.
            ev.evaluate(guard = "allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("null guard is delegated, not short-circuited by this class") {
            val ev = TokenFlowGuardEvaluator()
            ev.evaluate(guard = null, instance = instance(), event = Event.of("advance")) shouldBe GuardResult.True
        }
    })
