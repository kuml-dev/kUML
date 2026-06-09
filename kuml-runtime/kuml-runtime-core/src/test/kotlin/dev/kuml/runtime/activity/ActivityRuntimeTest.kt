package dev.kuml.runtime.activity

import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.ActivityNodeKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * V2.0.18 — Unit tests for [ActivityRuntime] token-flow semantics.
 *
 * All tests use hand-built [ActivityRuntimeSpec] instances so they don't
 * depend on the SysML 2 metamodel or DSL. The [Sysml2ActivityAdapterTest]
 * covers the adapter layer.
 */
class ActivityRuntimeTest :
    FunSpec({

        // ── helpers ───────────────────────────────────────────────────────────

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

        fun objEdge(
            id: String,
            from: String,
            to: String,
            objectType: String? = null,
        ) = ActivityEdgeSpec(id = id, sourceNodeId = from, targetNodeId = to, isObjectFlow = true, objectType = objectType)

        fun runtime(
            vararg nodes: ActivityNodeSpec,
            edges: List<ActivityEdgeSpec> = emptyList(),
        ): ActivityRuntime {
            val spec =
                ActivityRuntimeSpec(
                    nodes = nodes.associateBy { it.id },
                    edges = edges,
                )
            return ActivityRuntime(spec = spec)
        }

        // ── 1. Linear: Initial → Action → Final ───────────────────────────────

        test("linear: Initial → Action → Final produces ActivityTerminated") {
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("act", ActivityNodeKind.Action, body = "doSomething()"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "init", "act"),
                            edge("e2", "act", "fin"),
                        ),
                )

            val (instance, trace) = rt.run()

            instance.isTerminated shouldBe true
            val terminated = trace.filterIsInstance<TraceEntry.ActivityTerminated>()
            terminated shouldHaveAtLeastSize 1
        }

        // ── 2. Decision with guard — correct branch selected ──────────────────

        test("decision: correct guarded branch is taken") {
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("dec", ActivityNodeKind.Decision),
                    node("yes", ActivityNodeKind.Action, body = "yes()"),
                    node("no", ActivityNodeKind.Action, body = "no()"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "init", "dec"),
                            edge("e2", "dec", "yes", guard = "allow"),
                            edge("e3", "dec", "no", guard = "!allow"),
                            edge("e4", "yes", "fin"),
                            edge("e5", "no", "fin"),
                        ),
                )

            val (instance, trace) = rt.run(eventContext = mapOf("allow" to true))

            instance.isTerminated shouldBe true
            val actionInvoked = trace.filterIsInstance<TraceEntry.ActivityActionInvoked>()
            val bodies = actionInvoked.map { it.body }
            bodies.contains("yes()") shouldBe true
            bodies.contains("no()") shouldBe false
        }

        // ── 3. Decision default branch ─────────────────────────────────────────

        test("decision: first unguarded edge taken when no guard matches") {
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("dec", ActivityNodeKind.Decision),
                    node("a", ActivityNodeKind.Action, body = "default()"),
                    node("b", ActivityNodeKind.Action, body = "guarded()"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "init", "dec"),
                            edge("e2", "dec", "b", guard = "allow"), // guard fails — allow not in context
                            edge("e3", "dec", "a"), // unguarded — taken as default
                            edge("e4", "a", "fin"),
                            edge("e5", "b", "fin"),
                        ),
                )

            val (instance, trace) = rt.run(eventContext = emptyMap())

            instance.isTerminated shouldBe true
            val invoked = trace.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
            invoked.contains("default()") shouldBe true
            invoked.contains("guarded()") shouldBe false
        }

        // ── 4. Fork then Join ─────────────────────────────────────────────────

        test("fork-join: tokens split at Fork, both branches execute, Join synchronises") {
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("fork", ActivityNodeKind.Fork),
                    node("left", ActivityNodeKind.Action, body = "left()"),
                    node("right", ActivityNodeKind.Action, body = "right()"),
                    node("join", ActivityNodeKind.Join),
                    node("end", ActivityNodeKind.Action, body = "after()"),
                    node("fin", ActivityNodeKind.Final),
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

            val (instance, trace) = rt.run()

            instance.isTerminated shouldBe true
            val forkEntry = trace.filterIsInstance<TraceEntry.ForkSplit>()
            forkEntry shouldHaveAtLeastSize 1
            forkEntry.first().nodeId shouldBe "fork"

            val joinEntry = trace.filterIsInstance<TraceEntry.JoinReached>()
            joinEntry shouldHaveAtLeastSize 1
            joinEntry.first().isReady shouldBe true

            val invoked = trace.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
            invoked.contains("left()") shouldBe true
            invoked.contains("right()") shouldBe true
            invoked.contains("after()") shouldBe true
        }

        // ── 5. FlowFinal: token consumed, other tokens continue ───────────────

        test("flow-final: FlowFinalConsumed emitted and other tokens continue") {
            // One path ends at FlowFinal; a second path ends at Final
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("fork", ActivityNodeKind.Fork),
                    node("a", ActivityNodeKind.Action, body = "a()"),
                    node("ff", ActivityNodeKind.FlowFinal),
                    node("b", ActivityNodeKind.Action, body = "b()"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "init", "fork"),
                            edge("e2", "fork", "a"),
                            edge("e3", "fork", "b"),
                            edge("e4", "a", "ff"),
                            edge("e5", "b", "fin"),
                        ),
                )

            val (instance, trace) = rt.run()

            instance.isTerminated shouldBe true
            val flowFinal = trace.filterIsInstance<TraceEntry.FlowFinalConsumed>()
            flowFinal shouldHaveAtLeastSize 1
            flowFinal.first().nodeId shouldBe "ff"

            val terminated = trace.filterIsInstance<TraceEntry.ActivityTerminated>()
            terminated shouldHaveAtLeastSize 1
        }

        // ── 6. Deadlock detection ─────────────────────────────────────────────

        test("deadlock: ActivityDeadlockException when Final not reachable") {
            // Action has no outgoing edges → deadlock
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("act", ActivityNodeKind.Action, body = "stuck()"),
                    edges =
                        listOf(
                            edge("e1", "init", "act"),
                            // No edge from act → Final: deadlock
                        ),
                )

            val ex = shouldThrow<ActivityDeadlockException> { rt.run() }
            ex.message!! shouldContain "deadlocked"
        }

        // ── 7. Snapshot serialization roundtrip ───────────────────────────────

        test("ActivityInstance serializes and deserializes correctly") {
            val original =
                ActivityInstance(
                    tokenCounts = mapOf("nodeA" to 2, "nodeB" to 1),
                    isTerminated = false,
                    clock = 42L,
                )

            val json = KumlRuntimeJson.encodeToString(ActivityInstance.serializer(), original)
            val decoded = KumlRuntimeJson.decodeFromString(ActivityInstance.serializer(), json)

            decoded shouldBe original
        }

        // ── 8. Deterministic ordering ─────────────────────────────────────────

        test("same model produces same trace on multiple runs") {
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("act", ActivityNodeKind.Action, body = "do()"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "init", "act"),
                            edge("e2", "act", "fin"),
                        ),
                )

            val (_, trace1) = rt.run()
            val (_, trace2) = rt.run()

            val bodies1 = trace1.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
            val bodies2 = trace2.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
            bodies1 shouldBe bodies2
        }

        // ── 9. maxSteps guard ─────────────────────────────────────────────────

        test("maxSteps guard throws when infinite loop model exceeds limit") {
            // Self-loop on Action — never terminates
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("act", ActivityNodeKind.Action, body = "loop()"),
                    edges =
                        listOf(
                            edge("e1", "init", "act"),
                            edge("e2", "act", "act"), // self-loop: infinite
                        ),
                )

            val ex =
                shouldThrow<ActivityDeadlockException> {
                    rt.run(maxSteps = 5)
                }
            ex.message!! shouldContain "maxSteps=5"
        }

        // ── 10. ObjectFlow edge flagged in spec ───────────────────────────────

        test("ObjectFlow edge has isObjectFlow=true in spec") {
            val edge =
                ActivityEdgeSpec(
                    id = "obj1",
                    sourceNodeId = "a",
                    targetNodeId = "b",
                    isObjectFlow = true,
                    objectType = "Order",
                )

            edge.isObjectFlow shouldBe true
            edge.objectType shouldBe "Order"
        }

        // ── 11. Empty model: start() throws clear error ───────────────────────

        test("start() throws clear error when no Initial node found") {
            val rt =
                runtime(
                    node("act", ActivityNodeKind.Action),
                )

            val ex = shouldThrow<IllegalArgumentException> { rt.start() }
            ex.message!! shouldContain "Initial node"
        }

        // ── 12. Multi-initial: two Initial nodes both receive tokens ──────────

        test("multi-initial: two Initial nodes both receive tokens") {
            val rt =
                runtime(
                    node("i1", ActivityNodeKind.Initial),
                    node("i2", ActivityNodeKind.Initial),
                    node("a1", ActivityNodeKind.Action, body = "a1()"),
                    node("a2", ActivityNodeKind.Action, body = "a2()"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "i1", "a1"),
                            edge("e2", "i2", "a2"),
                            edge("e3", "a1", "fin"),
                            edge("e4", "a2", "fin"),
                        ),
                )

            val (instance, trace) = rt.run()

            instance.isTerminated shouldBe true
            val invoked = trace.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body }
            invoked.contains("a1()") shouldBe true
            invoked.contains("a2()") shouldBe true
        }

        // ── 13. Merge node: passes tokens through without sync ────────────────

        test("merge: passes tokens through without sync") {
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("merge", ActivityNodeKind.Merge),
                    node("act", ActivityNodeKind.Action, body = "after()"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "init", "merge"),
                            edge("e2", "merge", "act"),
                            edge("e3", "act", "fin"),
                        ),
                )

            val (instance, trace) = rt.run()

            instance.isTerminated shouldBe true
            val invoked = trace.filterIsInstance<TraceEntry.ActivityActionInvoked>()
            invoked.any { it.body == "after()" } shouldBe true
        }

        // ── 14. ActionInvoked contains action body string ─────────────────────

        test("ActionInvoked trace entry contains the action body string") {
            val rt =
                runtime(
                    node("init", ActivityNodeKind.Initial),
                    node("act", ActivityNodeKind.Action, body = "myAction(42)"),
                    node("fin", ActivityNodeKind.Final),
                    edges =
                        listOf(
                            edge("e1", "init", "act"),
                            edge("e2", "act", "fin"),
                        ),
                )

            val (_, trace) = rt.run()

            val invoked = trace.filterIsInstance<TraceEntry.ActivityActionInvoked>()
            invoked.any { it.body == "myAction(42)" } shouldBe true
        }
    })
