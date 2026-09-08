package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.TraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TokenFlowSequenceTest :
    FunSpec({
        test("linear: INITIAL -> ACTION -> TERMINATE_FINAL terminates with expected trace order") {
            val spec =
                TestFixtures.spec(
                    nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "act"), TestFixtures.terminateFinal()),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "act"),
                            TestFixtures.edge(id = "e2", from = "act", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace

                result.outcome shouldBe TokenFlowOutcome.Terminated
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("act")
                full.last() shouldBe full.filterIsInstance<TraceEntry.ActivityTerminated>().last()
            }
        }

        test("ACTION with two outgoing edges places a token on each (implicit AND-split)") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.action(id = "act"),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "b"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "act"),
                            TestFixtures.edge(id = "e2", from = "act", to = "a"),
                            TestFixtures.edge(id = "e3", from = "act", to = "b"),
                            TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "b", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val invoked = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
                invoked.contains("a") shouldBe true
                invoked.contains("b") shouldBe true
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("ACTION with two incoming edges fires twice (implicit XOR-merge, no synchronisation)") {
            // Uses FLOW_FINAL (not TERMINATE_FINAL) so the second `merge` firing isn't
            // pre-empted by a TERMINATE_FINAL racing ahead of it in a later step.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.gateway(id = "split", family = GatewayFamily.PARALLEL, converging = false, diverging = true),
                            TestFixtures.initial(),
                            TestFixtures.action(id = "merge"),
                            TestFixtures.flowFinal("ff"),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "merge"),
                            TestFixtures.edge(id = "e3", from = "split", to = "merge"),
                            TestFixtures.edge(id = "e4", from = "merge", to = "ff"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace
                val invokedCount = full.filterIsInstance<TraceEntry.ActivityActionInvoked>().count { it.body == "merge" }
                invokedCount shouldBe 2
                full.filterIsInstance<TraceEntry.FlowFinalConsumed>().size shouldBe 2
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }
    })
