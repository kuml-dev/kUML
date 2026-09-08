package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.activity.ActivityInstance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class TokenFlowParallelGatewayTest :
    FunSpec({
        test("fork-join: both branches execute, join fires exactly once with isReady=true") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "fork", family = GatewayFamily.PARALLEL, converging = false, diverging = true),
                            TestFixtures.action(id = "left"),
                            TestFixtures.action(id = "right"),
                            TestFixtures.gateway(id = "join", family = GatewayFamily.PARALLEL, converging = true, diverging = false),
                            TestFixtures.action(id = "after"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "fork"),
                            TestFixtures.edge(id = "e2", from = "fork", to = "left"),
                            TestFixtures.edge(id = "e3", from = "fork", to = "right"),
                            TestFixtures.edge(id = "e4", from = "left", to = "join"),
                            TestFixtures.edge(id = "e5", from = "right", to = "join"),
                            TestFixtures.edge(id = "e6", from = "join", to = "after"),
                            TestFixtures.edge(id = "e7", from = "after", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace

                full.filterIsInstance<TraceEntry.ForkSplit>() shouldHaveSize 1
                val joins = full.filterIsInstance<TraceEntry.JoinReached>()
                joins shouldHaveSize 1
                joins.first().isReady shouldBe true
                val invoked = full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
                invoked.contains("left") shouldBe true
                invoked.contains("right") shouldBe true
                invoked.contains("after") shouldBe true
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("join does not fire while only one branch has arrived") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial("i1"),
                            TestFixtures.action(id = "left"),
                            TestFixtures.gateway(id = "join", family = GatewayFamily.PARALLEL, converging = true, diverging = false),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "i1", to = "left"),
                            TestFixtures.edge(id = "e2", from = "left", to = "join"),
                            TestFixtures.edge(id = "e3", from = "join", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                // "join" also has an edge "e_missing" declared as incoming (from a node
                // that never fires) so it structurally requires a second arrival.
                val specWithMissingBranch =
                    TestFixtures.spec(
                        nodes = spec.nodes.values.toList() + TestFixtures.action(id = "neverFires"),
                        edges = spec.edges + TestFixtures.edge(id = "e4", from = "neverFires", to = "join"),
                    )
                TestFixtures.engineOf(spec = specWithMissingBranch).use { sandboxed2 ->
                    val start2 = sandboxed2.engine.start()
                    val result2 = sandboxed2.engine.run(initial = start2.instance)
                    // "join" can never fire (neverFires never gets a token) -> deadlock.
                    (result2.outcome is TokenFlowOutcome.Blocked) shouldBe true
                }
                // Sanity: the original (fully-satisfiable) spec does terminate.
                val result = sandboxed.engine.run(initial = start.instance)
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("two parallel edges from the SAME source to the same join require two arrivals") {
            // This is exactly the case Set<sourceNodeId>-based join tracking gets wrong
            // (it would collapse both arrivals from "fork" into one) — edge-based
            // joinEdgeTokens tracking (ADR-0015) must require both edges.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "fork", family = GatewayFamily.PARALLEL, converging = false, diverging = true),
                            TestFixtures.gateway(id = "join", family = GatewayFamily.PARALLEL, converging = true, diverging = false),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "fork"),
                            TestFixtures.edge(id = "e2", from = "fork", to = "join"),
                            TestFixtures.edge(id = "e3", from = "fork", to = "join"),
                            TestFixtures.edge(id = "e4", from = "join", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.JoinReached>() shouldHaveSize 1
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("MIXED parallel gateway (2 in, 2 out) synchronises then splits in the same firing, no synthetic ids") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial("i1"),
                            TestFixtures.initial("i2"),
                            TestFixtures.gateway(id = "mixed", family = GatewayFamily.PARALLEL, converging = true, diverging = true),
                            TestFixtures.action(id = "x"),
                            TestFixtures.action(id = "y"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "i1", to = "mixed"),
                            TestFixtures.edge(id = "e2", from = "i2", to = "mixed"),
                            TestFixtures.edge(id = "e3", from = "mixed", to = "x"),
                            TestFixtures.edge(id = "e4", from = "mixed", to = "y"),
                            TestFixtures.edge(id = "e5", from = "x", to = "fin"),
                            TestFixtures.edge(id = "e6", from = "y", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace

                full.filterIsInstance<TraceEntry.JoinReached>() shouldHaveSize 1
                full.filterIsInstance<TraceEntry.ForkSplit>() shouldHaveSize 1
                // No synthetic "mixed_merge"/"mixed_decision" ids anywhere in the trace.
                full.none { entryNodeId(it)?.contains("_merge") == true || entryNodeId(it)?.contains("_decision") == true } shouldBe true
                val invoked = full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
                invoked.contains("x") shouldBe true
                invoked.contains("y") shouldBe true
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("guards on a PARALLEL split's outgoing edges are ignored — all branches always fire, and validate() warns") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "fork", family = GatewayFamily.PARALLEL, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "b"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "fork"),
                            TestFixtures.edge(id = "e2", from = "fork", to = "a", guard = "neverTrue"),
                            TestFixtures.edge(id = "e3", from = "fork", to = "b"),
                            TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "b", to = "fin"),
                        ),
                )
            spec.validate().any { it.code == "PARALLEL_SPLIT_GUARD_IGNORED" } shouldBe true
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val invoked = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
                invoked.contains("a") shouldBe true
                invoked.contains("b") shouldBe true
            }
        }

        test(
            "GATEWAY with neither converging nor diverging set is a plain pass-through even when " +
                "family=PARALLEL, not a synchronisation point",
        ) {
            // BpmnTokenFlowAdapter computes converging/diverging structurally
            // (in-degree/out-degree, or an explicit GatewayDirection); a
            // single-in/single-out gateway with no explicit direction ends up
            // converging=false, diverging=false while still carrying whatever
            // `family` its BPMN gatewayType implies (PARALLEL here). Per the
            // documented contract ("A GATEWAY with neither converging nor
            // diverging set behaves as a plain pass-through, same as EXCLUSIVE
            // converging"), firing it must NOT dispatch on `family` — doing so
            // used to route this through consumeConvergence's PARALLEL branch
            // and emit a spurious JoinReached(isReady=true) for a gateway that
            // never actually synchronised anything.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "gw", family = GatewayFamily.PARALLEL, converging = false, diverging = false),
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

        test(
            "AND-join with UNEQUAL edge arrival counts consumes exactly one arrival per edge, " +
                "leaving the surplus correctly tracked instead of stranding a token",
        ) {
            // Regression for the bug where consumeConvergence(PARALLEL) removed
            // `incoming.size` tokens but then called clearJoinArrivals() — wiping
            // ALL recorded arrivals, including a surplus on an edge that delivered
            // more than once (e.g. two initial tokens funnelled through a single
            // upstream ACTION node that only consumes one token per firing, so it
            // fires twice before the join's other incoming edge delivers at all).
            // Since tokenCounts[join] always equals the sum of joinEdgeTokens[join]
            // (both are incremented together by placeToken/withJoinArrival), wiping
            // the map while only decrementing the token count by `incoming.size`
            // leaves an un-trackable orphan token that can never satisfy isReady()
            // again — a permanent (spurious) stall, not a genuine deadlock.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.gateway(id = "join", family = GatewayFamily.PARALLEL, converging = true, diverging = false),
                            TestFixtures.action(id = "after"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "eA", from = "a", to = "join"),
                            TestFixtures.edge(id = "eB", from = "b", to = "join"),
                            TestFixtures.edge(id = "e6", from = "join", to = "after"),
                            TestFixtures.edge(id = "e7", from = "after", to = "fin"),
                        ),
                )

            // Hand-built pre-state: "eA" has delivered twice, "eB" only once —
            // 3 tokens total sitting at "join", exactly mirroring what placeToken()
            // would have produced via two "eA" firings and one "eB" firing.
            val threeArrivals =
                ActivityInstance(
                    tokenCounts = mapOf("join" to 3),
                    joinEdgeTokens = mapOf("join" to mapOf("eA" to 2, "eB" to 1)),
                )

            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val step1 = sandboxed.engine.step(instance = threeArrivals)

                // Exactly one join completion (min(2,1) = 1 possible), forwarding
                // one token to "after" and leaving exactly one token — the
                // still-unmatched second "eA" arrival — at "join".
                step1.trace.filterIsInstance<TraceEntry.JoinReached>() shouldHaveSize 1
                step1.instance.tokenCounts["join"] shouldBe 1
                // Crucially: the leftover arrival is still correctly recorded
                // (NOT wiped) — this is what clearJoinArrivals() got wrong.
                step1.instance.joinEdgeTokens["join"] shouldBe mapOf("eA" to 1)

                // A genuine second "eB" arrival later completes a SECOND join
                // firing and fully drains both the token and its bookkeeping —
                // proving the leftover was a real, still-satisfiable arrival, not
                // permanently un-trackable debris.
                val secondArrivalPending =
                    ActivityInstance(
                        tokenCounts = mapOf("join" to 2),
                        joinEdgeTokens = mapOf("join" to mapOf("eA" to 1, "eB" to 1)),
                    )
                val step2 = sandboxed.engine.step(instance = secondArrivalPending)
                step2.trace.filterIsInstance<TraceEntry.JoinReached>() shouldHaveSize 1
                step2.instance.tokenCounts["join"] shouldBe null
                step2.instance.joinEdgeTokens["join"] shouldBe null
            }
        }
    })

private fun entryNodeId(entry: TraceEntry): String? =
    when (entry) {
        is TraceEntry.TokenPlaced -> entry.nodeId
        is TraceEntry.TokenConsumed -> entry.nodeId
        is TraceEntry.DecisionTaken -> entry.nodeId
        is TraceEntry.ForkSplit -> entry.nodeId
        is TraceEntry.JoinReached -> entry.nodeId
        is TraceEntry.ActivityActionInvoked -> entry.nodeId
        is TraceEntry.FlowFinalConsumed -> entry.nodeId
        else -> null
    }
