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
            executor.execute("temperature = 21", instance, noEvent)
            instance.variables["temperature"] shouldBe 21L
        }

        test("assign nested path creates maps") {
            val instance = emptyInstance()
            executor.execute("sensor.value = 42", instance, noEvent)
            @Suppress("UNCHECKED_CAST")
            val sensor = instance.variables["sensor"] as Map<String, Any?>
            sensor["value"] shouldBe 42L
        }

        test("whitelisted function executes without exception") {
            val policy2 = SandboxPolicy(allowedFunctions = setOf("log.info"))
            val exec2 = EffectExecutor(policy2)
            val instance = emptyInstance()
            exec2.execute("log.info('hello')", instance, noEvent)
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
                    strictExec.execute("log.info('hello')", instance, noEvent)
                }
            ex.name shouldBe "log.info"
        }

        test("reserved variable name throws ReservedVariableName") {
            val instance = emptyInstance()
            shouldThrow<SandboxException.ReservedVariableName> {
                executor.execute("self = 1", instance, noEvent)
            }
        }

        test("variable count limit enforced") {
            val limitedPolicy = SandboxPolicy(maxVariableCount = 2)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            // instance already starts with 0 vars after init
            limitedExec.execute("a = 1", instance, noEvent)
            limitedExec.execute("b = 2", instance, noEvent)
            shouldThrow<SandboxException.VariableLimitExceeded> {
                limitedExec.execute("c = 3", instance, noEvent)
            }
        }

        test("string length limit enforced") {
            val limitedPolicy = SandboxPolicy(maxStringLength = 5)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            shouldThrow<SandboxException.StringLengthExceeded> {
                limitedExec.execute("msg = 'toolong'", instance, noEvent)
            }
        }

        test("effect count limit enforced") {
            val limitedPolicy = SandboxPolicy(maxEffectsPerAction = 2)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            shouldThrow<SandboxException.TooManyEffects> {
                limitedExec.execute("a = 1; b = 2; c = 3", instance, noEvent)
            }
        }

        test("expression depth limit enforced") {
            val limitedPolicy = SandboxPolicy(maxExpressionDepth = 2)
            val limitedExec = EffectExecutor(limitedPolicy)
            val instance = emptyInstance()
            // depth(a + (b + (c + d))) = 3 levels deep → exceeds limit 2
            shouldThrow<SandboxException.ExpressionTooDeep> {
                limitedExec.execute("x = a + (b + (c + d))", instance, noEvent)
            }
        }

        test("parse failure throws ParseFailure") {
            val instance = emptyInstance()
            shouldThrow<SandboxException.ParseFailure> {
                executor.execute("@@@invalid###", instance, noEvent)
            }
        }

        test("blank action body is a no-op") {
            val instance = emptyInstance()
            executor.execute("   ", instance, noEvent)
            instance.variables.size shouldBe 0
        }
    })
