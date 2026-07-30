package dev.kuml.runtime.sandbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EffectExecutorTest :
    FunSpec({
        val policy = SandboxPolicy()
        val executor = EffectExecutor(policy)

        test("assign variable sets value in instance") {
            val instance = emptyInstance()
            executor.execute(actionBody = "temperature = 21", instance = instance, event = noEvent)
            instance.variables["temperature"] shouldBe 21L
        }

        test("assign nested path creates maps") {
            val instance = emptyInstance()
            executor.execute(actionBody = "sensor.value = 42", instance = instance, event = noEvent)
            @Suppress("UNCHECKED_CAST")
            val sensor = instance.variables["sensor"] as Map<String, Any?>
            sensor["value"] shouldBe 42L
        }

        test("whitelisted function executes without exception") {
            val policy2 = SandboxPolicy(allowedFunctions = setOf("log.info"))
            val exec2 = EffectExecutor(policy2)
            val instance = emptyInstance()
            exec2.execute(actionBody = "log.info('hello')", instance = instance, event = noEvent)
            // log.info appends to __log__
            @Suppress("UNCHECKED_CAST")
            val log = instance.variables["__log__"] as List<*>
            (log.isNotEmpty()) shouldBe true
        }

        test("disallowed function throws DisallowedFunction") {
            val strictExec = EffectExecutor(SandboxPolicy.Strict) // allowedFunctions = emptySet
            val instance = emptyInstance()
            val ex =
                shouldThrow<SandboxException.DisallowedFunction> {
                    strictExec.execute(actionBody = "log.info('hello')", instance = instance, event = noEvent)
                }
            ex.name shouldBe "log.info"
        }

        test("reserved variable name throws ReservedVariableName") {
            val instance = emptyInstance()
            shouldThrow<SandboxException.ReservedVariableName> {
                executor.execute(actionBody = "self = 1", instance = instance, event = noEvent)
            }
        }

        test("variable count limit enforced") {
            val limitedPolicy = SandboxPolicy(maxVariableCount = 2)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            // instance already starts with 0 vars after init
            limitedExec.execute(actionBody = "a = 1", instance = instance, event = noEvent)
            limitedExec.execute(actionBody = "b = 2", instance = instance, event = noEvent)
            shouldThrow<SandboxException.VariableLimitExceeded> {
                limitedExec.execute(actionBody = "c = 3", instance = instance, event = noEvent)
            }
        }

        test("string length limit enforced") {
            val limitedPolicy = SandboxPolicy(maxStringLength = 5)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            shouldThrow<SandboxException.StringLengthExceeded> {
                limitedExec.execute(actionBody = "msg = 'toolong'", instance = instance, event = noEvent)
            }
        }

        test("effect count limit enforced") {
            val limitedPolicy = SandboxPolicy(maxEffectsPerAction = 2)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            shouldThrow<SandboxException.TooManyEffects> {
                limitedExec.execute(actionBody = "a = 1; b = 2; c = 3", instance = instance, event = noEvent)
            }
        }

        test("expression depth limit enforced") {
            val limitedPolicy = SandboxPolicy(maxExpressionDepth = 2)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            // depth(a + (b + (c + d))) = 3 levels deep → exceeds limit 2
            shouldThrow<SandboxException.ExpressionTooDeep> {
                limitedExec.execute(actionBody = "x = a + (b + (c + d))", instance = instance, event = noEvent)
            }
        }

        test("parse failure throws ParseFailure") {
            val instance = emptyInstance()
            shouldThrow<SandboxException.ParseFailure> {
                executor.execute(actionBody = "@@@invalid###", instance = instance, event = noEvent)
            }
        }

        test("blank action body is a no-op") {
            val instance = emptyInstance()
            executor.execute(actionBody = "   ", instance = instance, event = noEvent)
            instance.variables.size shouldBe 0
        }
    })
