package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.TraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TokenFlowExclusiveGatewayTest :
    FunSpec({
        // e3 always carries a guard too (never bare-unguarded) so the "no default" cases
        // genuinely have no matching branch, instead of accidentally matching via an
        // unguarded edge (an unguarded, non-default edge is unconditionally true and
        // would otherwise be indistinguishable from "no coverage gap here at all").
        fun xorSplitSpec(defaultOnB: Boolean) =
            TestFixtures.spec(
                nodes =
                    listOf(
                        TestFixtures.initial(),
                        TestFixtures.gateway(id = "dec", family = GatewayFamily.EXCLUSIVE, converging = false, diverging = true),
                        TestFixtures.action(id = "a"),
                        TestFixtures.action(id = "b"),
                        TestFixtures.terminateFinal(),
                    ),
                edges =
                    listOf(
                        TestFixtures.edge(id = "e1", from = "init", to = "dec"),
                        TestFixtures.edge(id = "e2", from = "dec", to = "a", guard = "go"),
                        TestFixtures.edge(
                            id = "e3",
                            from = "dec",
                            to = "b",
                            guard = if (defaultOnB) null else "neverTrue",
                            isDefault = defaultOnB,
                        ),
                        TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                        TestFixtures.edge(id = "e5", from = "b", to = "fin"),
                    ),
            )

        test("guard true selects the guarded branch, DecisionTaken names the chosen edge") {
            TestFixtures.engineOf(spec = xorSplitSpec(defaultOnB = true)).use { sandboxed ->
                val start = sandboxed.engine.start(TokenFlowContext(mapOf("go" to true)))
                val result = sandboxed.engine.run(initial = start.instance, context = TokenFlowContext(mapOf("go" to true)))
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("a")
                full.filterIsInstance<TraceEntry.DecisionTaken>().first().chosenEdgeId shouldBe "e2"
            }
        }

        test("no guard matches -> default edge is taken (BPMN default-flow semantics)") {
            TestFixtures.engineOf(spec = xorSplitSpec(defaultOnB = true)).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("b")
                full.filterIsInstance<TraceEntry.DecisionTaken>().first().chosenEdgeId shouldBe "e3"
            }
        }

        test("no guard matches, no default -> token silently dropped, activity blocks (strictGuardCoverage=false)") {
            TestFixtures.engineOf(spec = xorSplitSpec(defaultOnB = false)).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>() shouldBe emptyList()
                // Marking is empty after the token silently vanished at "dec" -> the
                // empty-marking postcondition reports a normal (if unintended) termination,
                // not a deadlock — there is nothing left to be "stuck" on.
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("strictGuardCoverage=true turns an uncovered exclusive split into a hard Failed") {
            val engine =
                TestFixtures.engineOf(spec = xorSplitSpec(defaultOnB = false), options = TokenFlowOptions(strictGuardCoverage = true))
            engine.use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                (result.outcome is TokenFlowOutcome.Failed) shouldBe true
            }
        }

        test("default edge is skipped during condition evaluation even if it would also match") {
            // Same source, but the "default" edge b also carries a true guard: default
            // must still only be used as fallback, e2's guard wins.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "dec", family = GatewayFamily.EXCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "b"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "dec"),
                            TestFixtures.edge(id = "e2", from = "dec", to = "a", guard = "go"),
                            TestFixtures.edge(id = "e3", from = "dec", to = "b", isDefault = true),
                            TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "b", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start(TokenFlowContext(mapOf("go" to true)))
                val result = sandboxed.engine.run(initial = start.instance, context = TokenFlowContext(mapOf("go" to true)))
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("a")
            }
        }

        test(
            "GatewaySpec.defaultEdgeId (BPMN standards-conformant default=\"...\" attribute) is honoured " +
                "even when the target edge's own isDefault flag is false",
        ) {
            // Reproduces the BpmnXmlImporter mismatch: a standards-conformant
            // `<exclusiveGateway default="e3"/>` sets GatewaySpec.defaultEdgeId
            // ("e3") but never sets TokenFlowEdge.isDefault on "e3" itself — that
            // flag only comes from the non-standard `isDefault="true"` attribute.
            // "e3" also carries a (never-true) guard, as some modelling tools
            // leave a documentation-only condition on the default flow even
            // though BPMN 2.0 says it must be skipped during condition
            // evaluation. Before the fix, defaultEdgeId was parsed but never
            // consulted here: neither edge's guard matches, isDefault is false
            // on both, so `chosen == null` and the token was silently dropped
            // (with strictGuardCoverage=false, the default) instead of following
            // the declared default flow.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(
                                id = "dec",
                                family = GatewayFamily.EXCLUSIVE,
                                converging = false,
                                diverging = true,
                                defaultEdgeId = "e3",
                            ),
                            TestFixtures.action(id = "a"),
                            TestFixtures.action(id = "b"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "dec"),
                            TestFixtures.edge(id = "e2", from = "dec", to = "a", guard = "neverTrue"),
                            // The intended default flow: defaultEdgeId="e3" above, but
                            // isDefault stays false and it carries its own false guard —
                            // exactly the standards-conformant-but-non-isDefault shape.
                            TestFixtures.edge(id = "e3", from = "dec", to = "b", guard = "alsoNeverTrue"),
                            TestFixtures.edge(id = "e4", from = "a", to = "fin"),
                            TestFixtures.edge(id = "e5", from = "b", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                val full = start.trace + result.trace
                full.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("b")
                full.filterIsInstance<TraceEntry.DecisionTaken>().first().chosenEdgeId shouldBe "e3"
                result.outcome shouldBe TokenFlowOutcome.Terminated
            }
        }

        test("XOR-merge (converging, non-diverging) passes each token through without synchronisation") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "merge", family = GatewayFamily.EXCLUSIVE, converging = true, diverging = false),
                            TestFixtures.action(id = "after"),
                            TestFixtures.terminateFinal(),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "merge"),
                            TestFixtures.edge(id = "e2", from = "merge", to = "after"),
                            TestFixtures.edge(id = "e3", from = "after", to = "fin"),
                        ),
                )
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                result.outcome shouldBe TokenFlowOutcome.Terminated
                // No extra JoinReached / synchronisation entry for a plain merge.
                (start.trace + result.trace).filterIsInstance<TraceEntry.JoinReached>() shouldBe emptyList()
            }
        }
    })
