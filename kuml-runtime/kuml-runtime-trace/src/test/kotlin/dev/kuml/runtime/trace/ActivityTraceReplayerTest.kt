package dev.kuml.runtime.trace

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.activity.ActivityDeadlockException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ActivityTraceReplayerTest :
    FunSpec({

        // ── happy-path: linear activity ───────────────────────────────────────

        test("happy-path roundtrip linear activity — isMatch true") {
            val runtime = simpleLinearActivity()
            val original = simulateActToTraceFile(runtime)

            val report = ActivityTraceReplayer().replay(runtime, original)

            report.isMatch shouldBe true
            report.originalSize shouldBe original.entries.size
            report.actualSize shouldBe original.entries.size
        }

        // ── happy-path: decision activity with valid=true ─────────────────────

        test("happy-path roundtrip decision activity valid=true — isMatch true") {
            val runtime = decisionActivity()
            val ctx = mapOf<String, Any>("valid" to true)
            val original = simulateActToTraceFile(runtime, ctx)

            val report = ActivityTraceReplayer().replay(runtime, original, eventContext = ctx)

            report.isMatch shouldBe true
        }

        // ── mismatch when eventContext differs ─────────────────────────────────

        test("mismatch when eventContext differs from original recording") {
            val runtime = decisionActivity()
            // Record original with valid=true
            val original = simulateActToTraceFile(runtime, mapOf("valid" to true))

            // Replay with valid=false — different branch taken → different trace
            val report =
                ActivityTraceReplayer().replay(
                    runtime,
                    original,
                    eventContext = mapOf("valid" to false),
                )

            report.isMatch shouldBe false
        }

        // ── determinism ───────────────────────────────────────────────────────

        test("replay is deterministic — two calls produce identical actualTrace") {
            val runtime = simpleLinearActivity()
            val original = simulateActToTraceFile(runtime)

            val report1 = ActivityTraceReplayer().replay(runtime, original)
            val report2 = ActivityTraceReplayer().replay(runtime, original)

            report1.actualTrace.size shouldBe report2.actualTrace.size
            for (i in report1.actualTrace.indices) {
                report1.actualTrace[i]::class shouldBe report2.actualTrace[i]::class
            }
            report1.isMatch shouldBe report2.isMatch
        }

        // ── throws for STM trace ───────────────────────────────────────────────

        test("throws UnsupportedTraceFlavourException for STM-flavoured trace") {
            val runtime = simpleLinearActivity()
            val stmTrace =
                activityTraceFile().copy(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0L, timestamp = "", vertexId = "A"),
                            TraceEntry.Terminated(seqNo = 1L, timestamp = "", finalVertexId = "fin"),
                        ),
                )

            shouldThrow<UnsupportedTraceFlavourException> {
                ActivityTraceReplayer().replay(runtime, stmTrace)
            }
        }

        // ── throws for MIXED trace ─────────────────────────────────────────────

        test("throws UnsupportedTraceFlavourException for MIXED trace") {
            val runtime = simpleLinearActivity()
            val mixedTrace =
                activityTraceFile().copy(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0L, timestamp = "", vertexId = "A"),
                            TraceEntry.TokenPlaced(seqNo = 1L, timestamp = "", nodeId = "n1", clock = 0L),
                        ),
                )

            shouldThrow<UnsupportedTraceFlavourException> {
                ActivityTraceReplayer().replay(runtime, mixedTrace)
            }
        }

        // ── deadlock: throws ActivityDeadlockException ────────────────────────

        test("propagates ActivityDeadlockException for infinite-loop with maxSteps=5") {
            // loopActivity loops work→work indefinitely — hits maxSteps guard which always throws
            val loopRuntime = loopActivity()
            val fakeOriginal = simulateActToTraceFile(simpleLinearActivity()) // valid ACTIVITY trace

            shouldThrow<ActivityDeadlockException> {
                ActivityTraceReplayer().replay(
                    loopRuntime,
                    fakeOriginal,
                    maxSteps = 5,
                    failOnDeadlock = true,
                )
            }
        }

        // ── failOnDeadlock=false returns partial trace without throwing ────────

        test("failOnDeadlock=false returns partial trace without throwing") {
            // deadlockActivity has a token stuck with no enabled transitions — "nothing fired" path
            // which respects failOnDeadlock=false
            val deadlockRuntime = deadlockActivity()
            val fakeOriginal = simulateActToTraceFile(simpleLinearActivity())

            // Should not throw despite deadlock
            val report =
                ActivityTraceReplayer().replay(
                    deadlockRuntime,
                    fakeOriginal,
                    maxSteps = 100,
                    failOnDeadlock = false,
                )

            // The trace will have the startTrace entries (TokenPlaced + Initial firing) but no Final
            report.actualTrace.isNotEmpty() shouldBe true
        }

        // ── modelId mismatch ──────────────────────────────────────────────────

        test("throws IllegalArgumentException on modelId mismatch") {
            val runtime = simpleLinearActivity()
            val original = simulateActToTraceFile(runtime, modelId = "ModelA")

            shouldThrow<IllegalArgumentException> {
                ActivityTraceReplayer().replay(
                    runtime,
                    original,
                    modelId = "ModelB",
                )
            }
        }

        // ── toHumanReadable verbose ───────────────────────────────────────────

        test("toHumanReadable verbose shows eventContext on mismatch") {
            val runtime = decisionActivity()
            val original = simulateActToTraceFile(runtime, mapOf("valid" to true))
            val report =
                ActivityTraceReplayer().replay(
                    runtime,
                    original,
                    eventContext = mapOf("valid" to false),
                )

            val text = report.toHumanReadable(verbose = true)

            report.isMatch shouldBe false
            text shouldContain "valid"
        }

        // ── fork-join roundtrip ───────────────────────────────────────────────

        test("fork-join activity replays identically") {
            val runtime = forkJoinActivity()
            val original = simulateActToTraceFile(runtime)

            val report = ActivityTraceReplayer().replay(runtime, original)

            report.isMatch shouldBe true
        }

        // ── startTrace included in actualTrace ────────────────────────────────

        test("startTrace is included in actualTrace — first entry is TokenPlaced for Initial node") {
            val runtime = simpleLinearActivity()
            val original = simulateActToTraceFile(runtime)

            val report = ActivityTraceReplayer().replay(runtime, original)

            val first = report.actualTrace.firstOrNull()
            (first is TraceEntry.TokenPlaced) shouldBe true
            (first as TraceEntry.TokenPlaced).nodeId shouldBe "init"
        }
    })

// ── helper: activity that loops (no Final) ────────────────────────────────────

/**
 * Returns an ActivityRuntime that loops: Initial → Action → Action (loop back)
 * — it never reaches a Final node, so run() will hit maxSteps and always throw.
 */
private fun loopActivity(): dev.kuml.runtime.activity.ActivityRuntime {
    val nodes =
        mapOf(
            "init" to
                dev.kuml.runtime.activity.ActivityNodeSpec(
                    id = "init",
                    kind = dev.kuml.sysml2.ActivityNodeKind.Initial,
                ),
            "work" to
                dev.kuml.runtime.activity.ActivityNodeSpec(
                    id = "work",
                    kind = dev.kuml.sysml2.ActivityNodeKind.Action,
                    actionBody = "loop()",
                ),
        )
    val edges =
        listOf(
            dev.kuml.runtime.activity
                .ActivityEdgeSpec(id = "e1", sourceNodeId = "init", targetNodeId = "work"),
            dev.kuml.runtime.activity
                .ActivityEdgeSpec(id = "e2", sourceNodeId = "work", targetNodeId = "work"),
        )
    return dev.kuml.runtime.activity.ActivityRuntime(
        spec =
            dev.kuml.runtime.activity
                .ActivityRuntimeSpec(nodes = nodes, edges = edges),
    )
}

/**
 * Returns an ActivityRuntime that deadlocks: Initial → Action (dead end, no outgoing edges).
 * After the Initial fires and the Action fires (consuming its token with no outgoing edges),
 * there are no tokens left and no Final reached — "nothing fired" deadlock path.
 *
 * Wait — Action with no outgoing edges will consume its token and leave nothing behind,
 * so the next step finds nothing ready. That's the "nothing fired" path respecting failOnDeadlock.
 */
private fun deadlockActivity(): dev.kuml.runtime.activity.ActivityRuntime {
    val nodes =
        mapOf(
            "init" to
                dev.kuml.runtime.activity.ActivityNodeSpec(
                    id = "init",
                    kind = dev.kuml.sysml2.ActivityNodeKind.Initial,
                ),
            "deadEnd" to
                dev.kuml.runtime.activity.ActivityNodeSpec(
                    id = "deadEnd",
                    kind = dev.kuml.sysml2.ActivityNodeKind.Action,
                    actionBody = "deadEnd()",
                ),
        )
    val edges =
        listOf(
            dev.kuml.runtime.activity
                .ActivityEdgeSpec(id = "e1", sourceNodeId = "init", targetNodeId = "deadEnd"),
            // No outgoing edge from deadEnd → token consumed, nothing placed → deadlock
        )
    return dev.kuml.runtime.activity.ActivityRuntime(
        spec =
            dev.kuml.runtime.activity
                .ActivityRuntimeSpec(nodes = nodes, edges = edges),
    )
}
