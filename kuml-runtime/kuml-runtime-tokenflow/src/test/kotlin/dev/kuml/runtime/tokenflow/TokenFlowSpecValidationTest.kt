package dev.kuml.runtime.tokenflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TokenFlowSpecValidationTest :
    FunSpec({
        test("no INITIAL node -> ERROR NO_INITIAL_NODE") {
            val spec = TestFixtures.spec(nodes = listOf(TestFixtures.action(id = "a")), edges = emptyList())
            spec.validate().any { it.severity == TokenFlowSeverity.ERROR && it.code == "NO_INITIAL_NODE" } shouldBe true
        }

        test("dangling edge (unknown target) -> ERROR DANGLING_EDGE") {
            val spec =
                TestFixtures.spec(
                    nodes = listOf(TestFixtures.initial()),
                    edges = listOf(TestFixtures.edge(id = "e1", from = "init", to = "doesNotExist")),
                )
            spec.validate().any { it.severity == TokenFlowSeverity.ERROR && it.code == "DANGLING_EDGE" } shouldBe true
        }

        test("duplicate node id -> ERROR DUPLICATE_NODE_ID") {
            val spec = TestFixtures.spec(nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "init")), edges = emptyList())
            spec.validate().any { it.severity == TokenFlowSeverity.ERROR && it.code == "DUPLICATE_NODE_ID" } shouldBe true
        }

        test("duplicate edge id -> ERROR DUPLICATE_EDGE_ID") {
            val spec =
                TestFixtures.spec(
                    nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "a"), TestFixtures.action(id = "b")),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "a"),
                            TestFixtures.edge(id = "e1", from = "init", to = "b"),
                        ),
                )
            spec.validate().any { it.severity == TokenFlowSeverity.ERROR && it.code == "DUPLICATE_EDGE_ID" } shouldBe true
        }

        test("unreachable node -> WARNING UNREACHABLE_NODE") {
            val spec =
                TestFixtures.spec(
                    nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "a"), TestFixtures.action(id = "orphan")),
                    edges = listOf(TestFixtures.edge(id = "e1", from = "init", to = "a")),
                )
            spec.validate().any {
                it.severity == TokenFlowSeverity.WARNING && it.code == "UNREACHABLE_NODE" && it.elementId == "orphan"
            } shouldBe
                true
        }

        test("exclusive split with no default and no unguarded edge -> WARNING NO_DEFAULT_BRANCH") {
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "dec", family = GatewayFamily.EXCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "dec"),
                            TestFixtures.edge(id = "e2", from = "dec", to = "a", guard = "onlyIfTrue"),
                        ),
                )
            spec.validate().any { it.severity == TokenFlowSeverity.WARNING && it.code == "NO_DEFAULT_BRANCH" } shouldBe true
        }

        test("inclusive split with no default and no unguarded edge -> WARNING NO_DEFAULT_BRANCH") {
            // Security review (feature/tokenflow-execution-engine, finding "INCLUSIVE-Diverge
            // verwirft den Token still"): this static check previously only fired for
            // GatewayFamily.EXCLUSIVE, so an INCLUSIVE split with the exact same coverage gap
            // got neither this warning nor any runtime signal.
            val spec =
                TestFixtures.spec(
                    nodes =
                        listOf(
                            TestFixtures.initial(),
                            TestFixtures.gateway(id = "split", family = GatewayFamily.INCLUSIVE, converging = false, diverging = true),
                            TestFixtures.action(id = "a"),
                        ),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "split"),
                            TestFixtures.edge(id = "e2", from = "split", to = "a", guard = "onlyIfTrue"),
                        ),
                )
            spec.validate().any { it.severity == TokenFlowSeverity.WARNING && it.code == "NO_DEFAULT_BRANCH" } shouldBe true
        }

        test("a well-formed spec has no ERROR issues") {
            val spec =
                TestFixtures.spec(
                    nodes = listOf(TestFixtures.initial(), TestFixtures.action(id = "a"), TestFixtures.terminateFinal()),
                    edges =
                        listOf(
                            TestFixtures.edge(id = "e1", from = "init", to = "a"),
                            TestFixtures.edge(id = "e2", from = "a", to = "fin"),
                        ),
                )
            spec.validate().none { it.severity == TokenFlowSeverity.ERROR } shouldBe true
        }
    })
