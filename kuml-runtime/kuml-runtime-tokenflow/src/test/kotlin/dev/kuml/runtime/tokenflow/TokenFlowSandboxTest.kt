package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.KVisibility
import kotlin.reflect.full.primaryConstructor

/**
 * Security tests (ADR-0015 §5, security fix B2 for the *new* engine): guard
 * evaluation must always be time-bounded, and there must be no way to
 * construct a [TokenFlowEngine] that bypasses that bound.
 */
class TokenFlowSandboxTest :
    FunSpec({
        test("a guard that blocks past the timeout is cancelled, does not hang the engine, and the edge is not taken") {
            val latch = java.util.concurrent.CountDownLatch(1)
            val blockingGuardEvaluator =
                dev.kuml.runtime.GuardEvaluator { guard, _, _ ->
                    if (guard == "slow") {
                        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        dev.kuml.runtime.GuardResult.True
                    } else {
                        dev.kuml.runtime.GuardResult.True
                    }
                }
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "dec", family = GatewayFamily.EXCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "slowBranch"),
                            TestFixtures.action(id = "fastBranch", body = "fastBranch"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "dec"),
                            TestFixtures.edge(id = "e2", from = "dec", to = "slowBranch", guard = "slow"),
                            TestFixtures.edge(id = "e3", from = "dec", to = "fastBranch", isDefault = true),
                            TestFixtures.edge(id = "e4", from = "slowBranch", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "fastBranch", to = "fin"),
                        ),
                )
            val timedOutEvaluator =
                TimeLimitedGuardEvaluator(delegate = blockingGuardEvaluator, policy = SandboxPolicy(guardTimeoutMs = 50L))
            try {
                val engine = TokenFlowEngine(spec = spec, guards = timedOutEvaluator)
                val start = engine.start()
                val result = engine.run(initial = start.instance)
                // "slow" guard times out -> Failed on the non-default edge -> falls
                // through to the default edge -> fastBranch is taken.
                val invoked =
                    (start.trace + result.trace).filterIsInstance<dev.kuml.runtime.TraceEntry.ActivityActionInvoked>().map {
                        it.body
                    }
                invoked shouldBe listOf("fastBranch")
                result.outcome shouldBe TokenFlowOutcome.Terminated
            } finally {
                latch.countDown()
                timedOutEvaluator.close()
            }
        }

        test("TokenFlowEngine has no public constructor — sandboxed() is the only construction path") {
            // A JVM `Modifier.isPublic` check would NOT prove this: Kotlin's `internal`
            // visibility has no bytecode representation for constructors (no mangling
            // applies to `<init>`), so an internal constructor still compiles to a
            // public JVM constructor. Only kotlin-reflect's own visibility metadata
            // (backed by the class's @Metadata annotation) can verify the doctrine.
            val ctor = TokenFlowEngine::class.primaryConstructor
            ctor?.visibility shouldBe KVisibility.INTERNAL
        }

        test("a guard that throws does not abort execution, edge is simply not taken") {
            val throwingEvaluator =
                dev.kuml.runtime.GuardEvaluator { guard, _, _ ->
                    if (guard == "boom") throw IllegalStateException("boom") else dev.kuml.runtime.GuardResult.True
                }
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "dec", family = GatewayFamily.EXCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "b", body = "b"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "dec"),
                            TestFixtures.edge(id = "e2", from = "dec", to = "a", guard = "boom"),
                            TestFixtures.edge(id = "e3", from = "dec", to = "b", isDefault = true),
                            TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "b", to = "fin"),
                        ),
                )
            // Guard evaluator that throws — TimeLimitedGuardEvaluator converts the thrown
            // exception into GuardResult.Failed (never propagates), so the engine itself
            // never needs its own try/catch around guard evaluation.
            val evaluator = TimeLimitedGuardEvaluator(delegate = throwingEvaluator, policy = SandboxPolicy())
            try {
                val engine = TokenFlowEngine(spec = spec, guards = evaluator)
                val result = engine.run(initial = engine.start().instance)
                result.outcome shouldBe TokenFlowOutcome.Terminated
            } finally {
                evaluator.close()
            }
        }
    })
