package dev.kuml.runtime.tokenflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Negative / DoS tests (ADR-0015 B5): `maxSteps` alone does not protect
 * against token explosion in a cyclic Fork — [TokenFlowLimits.maxTokens],
 * [TokenFlowLimits.maxTraceEntries], and [TokenFlowLimits.wallClockBudgetMs]
 * close that gap. Every test here must complete quickly — a bug that makes
 * the engine actually hang would otherwise hang the whole test suite, so
 * each check runs inside [withHardTimeout].
 */
class TokenFlowLimitsTest :
    FunSpec({
        test("self-loop hits maxSteps quickly, with a partial trace (not thrown, not discarded)") {
            withHardTimeout {
                val spec =
                    TestFixtures.spec(
                        nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "loop")),
                        edges =
                            listOf(
                                TestFixtures.edge(id = "e1", from = "init", to = "loop"),
                                TestFixtures.edge(id = "e2", from = "loop", to = "loop"),
                            ),
                    )
                TestFixtures.engineOf(spec = spec, limits = TokenFlowLimits(maxSteps = 5)).use { sandboxed ->
                    val start = sandboxed.engine.start()
                    val result = sandboxed.engine.run(initial = start.instance)
                    val outcome = result.outcome as? TokenFlowOutcome.LimitExceeded
                    outcome?.limit shouldBe "maxSteps"
                    (result.trace.isNotEmpty()) shouldBe true
                }
            }
        }

        test("Fork inside a cycle trips maxTokens long before maxSteps (token-explosion DoS guard)") {
            withHardTimeout {
                // init -> fork -> {back1, back2}; back1 -> fork, back2 -> fork (both branches
                // feed back). Because a node fires at most once per step regardless of how
                // many tokens are stacked on it (same rule as the legacy ActivityRuntime —
                // needed for parity/determinism), "fork" only ever consumes ONE token per
                // step even while it accumulates more — so the total live-token count grows
                // without bound every full fork/rejoin round instead of staying flat, which
                // it would if only one of the two branches fed back.
                val spec =
                    TestFixtures.spec(
                        nodes =
                            listOf(
                                TestFixtures.initial(),
                                TestFixtures.gateway(id = "fork", family = GatewayFamily.PARALLEL, converging = false, diverging = true),
                                TestFixtures.action(id = "back1"),
                                TestFixtures.action(id = "back2"),
                            ),
                        edges =
                            listOf(
                                TestFixtures.edge(id = "e1", from = "init", to = "fork"),
                                TestFixtures.edge(id = "e2", from = "fork", to = "back1"),
                                TestFixtures.edge(id = "e3", from = "fork", to = "back2"),
                                TestFixtures.edge(id = "e4", from = "back1", to = "fork"),
                                TestFixtures.edge(id = "e5", from = "back2", to = "fork"),
                            ),
                    )
                // maxSteps left generous (well above what's needed to reach maxTokens=64,
                // but a small, bounded number regardless of the exact growth rate — this
                // proves maxTokens trips FIRST, i.e. maxSteps alone would not have saved us
                // if it were left at the default (1000) or higher.
                val limits = TokenFlowLimits(maxSteps = 500, maxTokens = 64, wallClockBudgetMs = 4_000L)
                TestFixtures.engineOf(spec = spec, limits = limits).use { sandboxed ->
                    val start = sandboxed.engine.start()
                    val result = sandboxed.engine.run(initial = start.instance)
                    val outcome = result.outcome as? TokenFlowOutcome.LimitExceeded
                    outcome?.limit shouldBe "maxTokens"
                }
            }
        }

        test("maxTraceEntries trips on a chatty but non-exploding infinite loop") {
            withHardTimeout {
                val spec =
                    TestFixtures.spec(
                        nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "a"), TestFixtures.action(id = "b")),
                        edges =
                            listOf(
                                TestFixtures.edge(id = "e1", from = "init", to = "a"),
                                TestFixtures.edge(id = "e2", from = "a", to = "b"),
                                TestFixtures.edge(id = "e3", from = "b", to = "a"),
                            ),
                    )
                TestFixtures
                    .engineOf(
                        spec = spec,
                        limits = TokenFlowLimits(maxSteps = 100_000, maxTokens = 100_000, maxTraceEntries = 50, wallClockBudgetMs = 4_000L),
                    ).use { sandboxed ->
                        val start = sandboxed.engine.start()
                        val result = sandboxed.engine.run(initial = start.instance)
                        val outcome = result.outcome as? TokenFlowOutcome.LimitExceeded
                        outcome?.limit shouldBe "maxTraceEntries"
                    }
            }
        }

        test("wallClockBudgetMs trips even with generous step/token limits, using a fake clock (no real sleeping)") {
            val spec =
                TestFixtures.spec(
                    nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "a"), TestFixtures.action(id = "b")),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "a"),
                            TestFixtures.edge(id = "e2", from = "a", to = "b"),
                            TestFixtures.edge(id = "e3", from = "b", to = "a"),
                        ),
                )
            var calls = 0
            // First call establishes the deadline; every call after that reports it as
            // already exceeded — deterministic, no wall-clock sleeping required. Uses the
            // module-internal constructor directly (this test lives in the same module),
            // so no sandbox/reflection detour is needed just to inject a fake clock.
            val fakeClock: () -> Long = {
                calls++
                if (calls <= 1) 0L else Long.MAX_VALUE / 2
            }
            val engine =
                TokenFlowEngine(
                    spec = spec,
                    guards = dev.kuml.runtime.GuardEvaluator.AlwaysTrue,
                    limits = TokenFlowLimits(maxSteps = 100_000, maxTokens = 100_000, maxTraceEntries = 100_000),
                    nanoClock = fakeClock,
                )
            val result = engine.run(initial = engine.start().instance)
            val outcome = result.outcome as? TokenFlowOutcome.LimitExceeded
            outcome?.limit shouldBe "wallClock"
        }

        test("deadlock (join whose second branch never fires) reports Blocked, not LimitExceeded") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.action(id = "left"),
                            TestFixtures.gateway(id = "join", family = GatewayFamily.PARALLEL, converging = true, diverging = false),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "left"),
                            TestFixtures.edge(id = "e2", from = "left", to = "join"),
                            // "join" also has an edge declared FROM a node that never
                            // receives a token, so it structurally needs a second arrival.
                            TestFixtures.edge(id = "e3", from = "fin", to = "join"),
                            TestFixtures.edge(id = "e4", from = "join", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                (result.outcome is TokenFlowOutcome.Blocked) shouldBe true
            }
        }
    })

/**
 * Runs [block] on a separate thread with a hard 5-second wall-clock bound —
 * if a DoS-guard regression made the engine actually hang, this fails the
 * test instead of hanging the whole suite.
 */
private fun withHardTimeout(block: () -> Unit) {
    val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tokenflow-limits-test").apply { isDaemon = true } }
    try {
        val future = executor.submit(block)
        future.get(5, TimeUnit.SECONDS)
    } finally {
        executor.shutdownNow()
    }
}
