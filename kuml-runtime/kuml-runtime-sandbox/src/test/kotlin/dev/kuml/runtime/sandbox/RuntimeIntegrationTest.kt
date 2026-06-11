package dev.kuml.runtime.sandbox

import dev.kuml.runtime.EffectInvoker
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RuntimeIntegrationTest :
    FunSpec({

        test("NoOp invoker leaves behaviour unchanged — variables not mutated") {
            val sm = smWith(entry = "x = 42")
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue, effects = EffectInvoker.NoOp)
            val instance = rt.start(sm)
            rt.step(instance, goEvent)
            // NoOp: entry action text is logged but not executed → x stays absent
            instance.variables["x"] shouldBe null
        }

        test("assignment in entry action mutates variables when SandboxEffectInvoker active") {
            val sm = smWith(entry = "temperature = 21")
            val executor = EffectExecutor(SandboxPolicy())
            val invoker = SandboxEffectInvoker(executor)
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue, effects = invoker)
            val instance = rt.start(sm)
            rt.step(instance, goEvent)
            instance.variables["temperature"] shouldBe 21L
        }

        test("disallowed function in entry causes rollback") {
            val sm = smWith(entry = "log.info('hi')")
            val strictExecutor = EffectExecutor(SandboxPolicy.Strict)
            val invoker = SandboxEffectInvoker(strictExecutor)
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue, effects = invoker)
            val instance = rt.start(sm)
            val stateBeforeStep = instance.currentVertices.map { it.id }
            val result = rt.step(instance, goEvent)
            // Rollback: state should revert to pre-step configuration
            result.shouldBeInstanceOf<StepResult.Error>()
            instance.currentVertices.map { it.id } shouldBe stateBeforeStep
        }

        test("slow guard via TimeLimitedGuardEvaluator yields Stayed when timed out") {
            val sm = smWith(transitionGuard = "slowGuard")
            val slowDelegate =
                GuardEvaluator { _, _, _ ->
                    Thread.sleep(2_000)
                    GuardResult.True
                }
            val tlge = TimeLimitedGuardEvaluator(slowDelegate, SandboxPolicy(guardTimeoutMs = 50))
            val rt = StateMachineRuntime(guards = tlge)
            val instance = rt.start(sm)
            // Guard times out → treated as failed → no transition → Stayed
            val result = rt.step(instance, goEvent)
            result.shouldBeInstanceOf<StepResult.Stayed>()
            tlge.close()
        }

        test("end-to-end: SandboxEffectInvoker + OclGuardEvaluator + assignment") {
            val sm = smWith(transitionEffect = "count = 10", transitionGuard = "true")
            val executor = EffectExecutor(SandboxPolicy())
            val invoker = SandboxEffectInvoker(executor)
            val rt = StateMachineRuntime(guards = OclGuardEvaluator(), effects = invoker)
            val instance = rt.start(sm)
            val result = rt.step(instance, goEvent)
            result.shouldBeInstanceOf<StepResult.Transitioned>()
            instance.variables["count"] shouldBe 10L
        }
    })
