package dev.kuml.runtime.sandbox

import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.OclGuardEvaluator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class TimeLimitedGuardEvaluatorTest :
    FunSpec({
        test("fast guard passes through delegate result") {
            val delegate = GuardEvaluator { _, _, _ -> GuardResult.True }
            val ev = TimeLimitedGuardEvaluator(delegate, SandboxPolicy(guardTimeoutMs = 500))
            val instance = emptyInstance()
            ev.evaluate("x > 0", instance, noEvent) shouldBe GuardResult.True
            ev.close()
        }

        test("slow guard times out and returns GuardResult.Failed") {
            val delegate =
                GuardEvaluator { _, _, _ ->
                    Thread.sleep(2_000) // 2 seconds — well past timeout
                    GuardResult.True
                }
            val ev = TimeLimitedGuardEvaluator(delegate, SandboxPolicy(guardTimeoutMs = 50))
            val instance = emptyInstance()
            val result = ev.evaluate("slow", instance, noEvent)
            result.shouldBeInstanceOf<GuardResult.Failed>()
            result.message shouldContain "timed out"
            ev.close()
        }

        test("delegate GuardResult.Failed is propagated") {
            val delegate = GuardEvaluator { _, _, _ -> GuardResult.Failed("bad guard") }
            val ev = TimeLimitedGuardEvaluator(delegate, SandboxPolicy())
            val instance = emptyInstance()
            val result = ev.evaluate("x", instance, noEvent)
            result.shouldBeInstanceOf<GuardResult.Failed>()
            result.message shouldBe "bad guard"
            ev.close()
        }

        test("null guard short-circuits to True without calling delegate") {
            var delegateCalled = false
            val delegate =
                GuardEvaluator { _, _, _ ->
                    delegateCalled = true
                    GuardResult.True
                }
            val ev = TimeLimitedGuardEvaluator(delegate, SandboxPolicy())
            val instance = emptyInstance()
            ev.evaluate(null, instance, noEvent) shouldBe GuardResult.True
            delegateCalled shouldBe false
            ev.close()
        }

        test("executor uses daemon threads") {
            val executor = TimeLimitedGuardEvaluator.defaultExecutor()
            // Submit a task and check the thread is daemon
            var isDaemon = false
            executor
                .submit {
                    isDaemon = Thread.currentThread().isDaemon
                }.get()
            isDaemon shouldBe true
            executor.shutdownNow()
        }

        test("close shuts down executor") {
            val ev =
                TimeLimitedGuardEvaluator(
                    OclGuardEvaluator(),
                    SandboxPolicy(),
                )
            ev.close()
            // After close, the executor is terminated
            val exec = ev
            // Evaluating after close should return Failed (RejectedExecutionException)
            val instance = emptyInstance()
            val result = exec.evaluate("true", instance, noEvent)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }
    })
