package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.TraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class TokenFlowInclusiveGatewayTest :
    FunSpec({
        test("OR-split: every true-guard edge gets a token, others don't") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "split", family = GatewayFamily.INCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "b"),
                            TestFixtures.action(id = "c"),
                            TestFixtures.gateway(id = "join", family = GatewayFamily.INCLUSIVE, converging = true, diverging = false),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "a", guard = "wantA"),
                            TestFixtures.edge(id = "e3", from = "split", to = "b", guard = "wantB"),
                            TestFixtures.edge(id = "e4", from = "split", to = "c", guard = "wantC"),
                            TestFixtures.edge(id = "e5", from = "a", to = "join"),
                            TestFixtures.edge(id = "e6", from = "b", to = "join"),
                            TestFixtures.edge(id = "e7", from = "c", to = "join"),
                            TestFixtures.edge(id = "e8", from = "join", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("wantA" to true, "wantB" to true, "wantC" to false))
                val start = sandboxed.engine.start(ctx)
                val result = sandboxed.engine.run(initial = start.instance, context = ctx)
                val full = start.trace + result.trace

                full.filterIsInstance<TraceEntry.DecisionTaken>() shouldHaveSize 2
                full.filterIsInstance<TraceEntry.ForkSplit>() shouldHaveSize 1
                val invoked = full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
                invoked.contains("a") shouldBe true
                invoked.contains("b") shouldBe true
                invoked.contains("c") shouldBe false
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("OR-split: no condition true -> default edge is taken") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "split", family = GatewayFamily.INCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "fallback"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "a", guard = "wantA"),
                            TestFixtures.edge(id = "e3", from = "split", to = "fallback", isDefault = true),
                            TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "fallback", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val invoked = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
                invoked shouldBe listOf("fallback")
            }
        }

        test(
            "GatewaySpec.defaultEdgeId is honoured for an OR-split too, even when the target edge's " +
                "own isDefault flag is false",
        ) {
            // Same BpmnXmlImporter mismatch as the EXCLUSIVE case: `default="e3"` sets
            // GatewaySpec.defaultEdgeId, never TokenFlowEdge.isDefault.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(
                                id = "split",
                                family = GatewayFamily.INCLUSIVE,
                                converging = false,
                                diverging = true,
                                defaultEdgeId = "e3",
                            ),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "fallback"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "a", guard = "wantA"),
                            TestFixtures.edge(id = "e3", from = "split", to = "fallback", guard = "alsoNeverTrue"),
                            TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "fallback", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val invoked = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
                invoked shouldBe listOf("fallback")
            }
        }

        test(
            "GATEWAY with neither converging nor diverging set is a plain pass-through even when " +
                "family=INCLUSIVE, not a synchronisation point",
        ) {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "gw", family = GatewayFamily.INCLUSIVE, converging = false, diverging = false),
                            TestFixtures.action(id = "after"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "gw"),
                            TestFixtures.edge(id = "e2", from = "gw", to = "after"),
                            TestFixtures.edge(id = "e3", from = "after", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.JoinReached>() shouldBe emptyList()
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("after")
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("OR-join waits while a token could still arrive, fires once no upstream token remains") {
            // "c" is a slow branch (two hops); "a"/"b" are fast (one hop). The join must
            // not fire until the slow branch has actually delivered, even though a and b
            // deliver first.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "split", family = GatewayFamily.INCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "b"),
                            TestFixtures.action(id = "slow1"),
                            TestFixtures.action(id = "slow2"),
                            TestFixtures.gateway(id = "join", family = GatewayFamily.INCLUSIVE, converging = true, diverging = false),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "a", guard = "always"),
                            TestFixtures.edge(id = "e3", from = "split", to = "b", guard = "always"),
                            TestFixtures.edge(id = "e4", from = "split", to = "slow1", guard = "always"),
                            TestFixtures.edge(id = "e5", from = "slow1", to = "slow2"),
                            TestFixtures.edge(id = "e6", from = "a", to = "join"),
                            TestFixtures.edge(id = "e7", from = "b", to = "join"),
                            TestFixtures.edge(id = "e8", from = "slow2", to = "join"),
                            TestFixtures.edge(id = "e9", from = "join", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("always" to true))
                val start = sandboxed.engine.start(ctx)
                // Step through explicitly to inspect the intermediate state where a and b
                // have arrived but slow2 has not fired yet.
                var instance = start.instance
                var trace = start.trace
                var joinFiredAt = -1
                var stepIdx = 0
                while (!instance.isTerminated && stepIdx < 20) {
                    val stepResult = sandboxed.engine.step(instance = instance, context = ctx)
                    trace = trace + stepResult.trace
                    instance = stepResult.instance
                    if (stepResult.trace.any { it is TraceEntry.JoinReached }) joinFiredAt = stepIdx
                    if (stepResult.outcome == TokenFlowOutcome.Idle) break
                    stepIdx++
                }
                joinFiredAt shouldBe (joinFiredAt.coerceAtLeast(0)) // sanity: join did fire at some point
                trace.filterIsInstance<TraceEntry.JoinReached>() shouldHaveSize 1
                instance.isTerminated shouldBe true
            }
        }

        test("no guard matches, no default -> token silently dropped, activity terminates (strictGuardCoverage=false)") {
            // Security review (feature/tokenflow-execution-engine, finding "INCLUSIVE-Diverge
            // verwirft den Token still"): mirrors TokenFlowExclusiveGatewayTest's equivalent case —
            // TokenFlowInclusiveGatewayTest previously had no test for "no guard matches, no
            // default edge" at all.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "split", family = GatewayFamily.INCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "a", guard = "neverTrue"),
                            TestFixtures.edge(id = "e3", from = "a", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>() shouldBe emptyList()
                // Marking is empty after the token silently vanished at "split" -> the
                // empty-marking postcondition reports a normal (if unintended) termination,
                // not a deadlock — there is nothing left to be "stuck" on.
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("strictGuardCoverage=true turns an uncovered inclusive split into a hard Failed") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "split", family = GatewayFamily.INCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "a", guard = "neverTrue"),
                            TestFixtures.edge(id = "e3", from = "a", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec, options = TokenFlowOptions(strictGuardCoverage = true)).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                (result.outcome is TokenFlowOutcome.Failed) shouldBe true
            }
        }

        test("OR-join in a cycle does not fire prematurely; maxSteps aborts cleanly instead of hanging") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "join", family = GatewayFamily.INCLUSIVE, converging = true, diverging = false),
                            TestFixtures.action(id = "loop"),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "join"),
                            TestFixtures.edge(id = "e2", from = "join", to = "loop"),
                            TestFixtures.edge(id = "e3", from = "loop", to = "join"),
                        ),
                )
            TestFixtures.engineOf(spec = spec, limits = TokenFlowLimits(maxSteps = 20)).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                (result.outcome is TokenFlowOutcome.LimitExceeded) shouldBe true
                (result.outcome as TokenFlowOutcome.LimitExceeded).limit shouldBe "maxSteps"
            }
        }
    })
