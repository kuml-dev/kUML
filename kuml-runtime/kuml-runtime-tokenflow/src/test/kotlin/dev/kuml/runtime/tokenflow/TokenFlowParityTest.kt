package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.TraceDiff
import dev.kuml.runtime.activity.ActivityEdgeSpec
import dev.kuml.runtime.activity.ActivityNodeSpec
import dev.kuml.runtime.activity.ActivityRuntime
import dev.kuml.runtime.activity.ActivityRuntimeSpec
import dev.kuml.runtime.tokenflow.adapter.toTokenFlowSpec
import dev.kuml.sysml2.ActivityNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * ADR-0015's most important test: for every fixture below, running the same
 * [ActivityRuntimeSpec] through the legacy [ActivityRuntime] and through
 * [TokenFlowEngine] (via `ActivityRuntimeSpecBridge`) must produce
 * byte-for-byte identical traces. If this test is red, the new engine is not
 * a drop-in replacement — `dev.kuml.io.svg.bpmn.smil.BpmnTokenTimelineBuilder`,
 * goldfile-based CLI tests, and `ActivityTraceReplayer` all depend on this
 * trace shape being stable across engines.
 *
 * Deadlock/maxSteps fixtures are intentionally excluded here — the two
 * engines report those via incompatible APIs (`ActivityRuntime` throws
 * [dev.kuml.runtime.activity.ActivityDeadlockException]; [TokenFlowEngine]
 * returns [TokenFlowOutcome.Blocked] / `LimitExceeded`) — see
 * [TokenFlowLimitsTest] for that engine's own coverage of those paths.
 */
class TokenFlowParityTest :
    FunSpec({
        fun node(
            id: String,
            kind: ActivityNodeKind,
            body: String? = null,
        ) = ActivityNodeSpec(id = id, kind = kind, actionBody = body)

        fun edge(
            id: String,
            from: String,
            to: String,
            guard: String? = null,
        ) = ActivityEdgeSpec(id = id, sourceNodeId = from, targetNodeId = to, guard = guard)

        fun assertParity(
            spec: ActivityRuntimeSpec,
            eventContext: Map<String, Any> = emptyMap(),
        ) {
            val legacy = ActivityRuntime(spec = spec)
            val (legacyInitial, legacyStartTrace) = legacy.start(eventContext)
            val (_, legacyRunTrace) = legacy.run(initial = legacyInitial, eventContext = eventContext)
            val expected = legacyStartTrace + legacyRunTrace

            val tokenFlowSpec = spec.toTokenFlowSpec(id = "parity", name = "parity")
            TestFixtures.engineOf(spec = tokenFlowSpec).use { sandboxed ->
                val context = TokenFlowContext(eventContext)
                val start = sandboxed.engine.start(context)
                val result = sandboxed.engine.run(initial = start.instance, context = context)
                val actual = start.trace + result.trace

                val report = TraceDiff.compare(actual = actual, expected = expected)
                withClue(report = report) { report.isMatch shouldBe true }
            }
        }

        test("parity: linear Initial -> Action -> Final") {
            val spec =
                ActivityRuntimeSpec(
                    nodes =
                        listOf(
                            node("init", ActivityNodeKind.Initial),
                            node("act", ActivityNodeKind.Action, "doSomething()"),
                            node("fin", ActivityNodeKind.Final),
                        ).associateBy { it.id },
                    edges = listOf(edge("e1", "init", "act"), edge("e2", "act", "fin")),
                )
            assertParity(spec)
        }

        test("parity: decision — correct guarded branch") {
            val spec =
                ActivityRuntimeSpec(
                    nodes =
                        listOf(
                            node("init", ActivityNodeKind.Initial),
                            node("dec", ActivityNodeKind.Decision),
                            node("yes", ActivityNodeKind.Action, "yes()"),
                            node("no", ActivityNodeKind.Action, "no()"),
                            node("fin", ActivityNodeKind.Final),
                        ).associateBy { it.id },
                    edges =
                        listOf(
                            edge("e1", "init", "dec"),
                            edge("e2", "dec", "yes", "allow"),
                            edge("e3", "dec", "no", "!allow"),
                            edge("e4", "yes", "fin"),
                            edge("e5", "no", "fin"),
                        ),
                )
            assertParity(spec, mapOf("allow" to true))
        }

        test("parity: decision — first unguarded edge as default") {
            val spec =
                ActivityRuntimeSpec(
                    nodes =
                        listOf(
                            node("init", ActivityNodeKind.Initial),
                            node("dec", ActivityNodeKind.Decision),
                            node("a", ActivityNodeKind.Action, "default()"),
                            node("b", ActivityNodeKind.Action, "guarded()"),
                            node("fin", ActivityNodeKind.Final),
                        ).associateBy { it.id },
                    edges =
                        listOf(
                            edge("e1", "init", "dec"),
                            edge("e2", "dec", "b", "allow"),
                            edge("e3", "dec", "a"),
                            edge("e4", "a", "fin"),
                            edge("e5", "b", "fin"),
                        ),
                )
            assertParity(spec)
        }

        test("parity: fork-join") {
            val spec =
                ActivityRuntimeSpec(
                    nodes =
                        listOf(
                            node("init", ActivityNodeKind.Initial),
                            node("fork", ActivityNodeKind.Fork),
                            node("left", ActivityNodeKind.Action, "left()"),
                            node("right", ActivityNodeKind.Action, "right()"),
                            node("join", ActivityNodeKind.Join),
                            node("end", ActivityNodeKind.Action, "after()"),
                            node("fin", ActivityNodeKind.Final),
                        ).associateBy { it.id },
                    edges =
                        listOf(
                            edge("e1", "init", "fork"),
                            edge("e2", "fork", "left"),
                            edge("e3", "fork", "right"),
                            edge("e4", "left", "join"),
                            edge("e5", "right", "join"),
                            edge("e6", "join", "end"),
                            edge("e7", "end", "fin"),
                        ),
                )
            assertParity(spec)
        }

        test("parity: flow-final alongside a Final branch") {
            val spec =
                ActivityRuntimeSpec(
                    nodes =
                        listOf(
                            node("init", ActivityNodeKind.Initial),
                            node("fork", ActivityNodeKind.Fork),
                            node("a", ActivityNodeKind.Action, "a()"),
                            node("ff", ActivityNodeKind.FlowFinal),
                            node("b", ActivityNodeKind.Action, "b()"),
                            node("fin", ActivityNodeKind.Final),
                        ).associateBy { it.id },
                    edges =
                        listOf(
                            edge("e1", "init", "fork"),
                            edge("e2", "fork", "a"),
                            edge("e3", "fork", "b"),
                            edge("e4", "a", "ff"),
                            edge("e5", "b", "fin"),
                        ),
                )
            assertParity(spec)
        }

        test("parity: multi-initial") {
            val spec =
                ActivityRuntimeSpec(
                    nodes =
                        listOf(
                            node("i1", ActivityNodeKind.Initial),
                            node("i2", ActivityNodeKind.Initial),
                            node("a1", ActivityNodeKind.Action, "a1()"),
                            node("a2", ActivityNodeKind.Action, "a2()"),
                            node("fin", ActivityNodeKind.Final),
                        ).associateBy { it.id },
                    edges = listOf(edge("e1", "i1", "a1"), edge("e2", "i2", "a2"), edge("e3", "a1", "fin"), edge("e4", "a2", "fin")),
                )
            assertParity(spec)
        }

        test("parity: merge passes tokens through without sync") {
            val spec =
                ActivityRuntimeSpec(
                    nodes =
                        listOf(
                            node("init", ActivityNodeKind.Initial),
                            node("merge", ActivityNodeKind.Merge),
                            node("act", ActivityNodeKind.Action, "after()"),
                            node("fin", ActivityNodeKind.Final),
                        ).associateBy { it.id },
                    edges = listOf(edge("e1", "init", "merge"), edge("e2", "merge", "act"), edge("e3", "act", "fin")),
                )
            assertParity(spec)
        }
    })

private fun withClue(
    report: TraceDiff.Report,
    block: () -> Unit,
) {
    try {
        block()
    } catch (t: AssertionError) {
        throw AssertionError(report.toHumanReadable(), t)
    }
}
