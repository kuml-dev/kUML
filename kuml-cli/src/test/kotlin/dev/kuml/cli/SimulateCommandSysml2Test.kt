package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.loadTrace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * V2.0.17 — CLI smoke test that proves `kuml simulate` accepts SysML 2
 * scripts and runs them through the [dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter].
 *
 * The traffic-light fixture drives the canonical Red → Green → Yellow → Red
 * cycle plus a terminal `powerOff` event into the `Off` final state. The
 * test asserts on the *state-sequence shape* of the produced trace so the
 * expected behaviour is end-to-end visible without coupling to wall-clock
 * timestamps.
 */
class SimulateCommandSysml2Test :
    FunSpec({

        val script = File("src/test/resources/simulate/sysml2/traffic-light.kuml.kts")
        val events = File("src/test/resources/simulate/sysml2/traffic-light.events.json")

        test("kuml simulate accepts a SysML 2 STM script and produces a trace") {
            val out = Files.createTempFile("kuml-simulate-sysml2-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "simulate",
                            script.absolutePath,
                            events.absolutePath,
                            "--out",
                            out.absolutePath,
                            "--epoch-clock",
                        ),
                    )
                result.statusCode shouldBe 0

                val trace = loadTrace(out).entries
                val stateSequence =
                    trace
                        .filterIsInstance<TraceEntry.StateEntered>()
                        .map { it.vertexId }

                // The INITIAL pseudostate fires its outgoing transition during
                // start() before any state-entered entry is emitted (the
                // runtime treats the initial vertex as transient — see V1.1.5
                // operational semantics). The visible state sequence is
                // therefore Red → Green → Yellow → Red → Off.
                stateSequence shouldContainInOrder
                    listOf("Red", "Green", "Yellow", "Red", "Off")

                val text = out.readText()
                text shouldContain "Terminated"
                text shouldContain "modelId"
            } finally {
                out.delete()
            }
        }

        // ── wave A2: virtual time, timers and prose guards ─────────────────────────

        val timerScript = File("src/test/resources/simulate/sysml2/timers.kuml.kts")

        fun runTimers(
            eventsFile: String,
            vararg extra: String,
        ): Triple<com.github.ajalt.clikt.testing.CliktCommandTestResult, File, List<TraceEntry>> {
            val out = Files.createTempFile("kuml-simulate-timers-", ".trace.json").toFile()
            val result =
                KumlCli().test(
                    listOf(
                        "simulate",
                        timerScript.absolutePath,
                        File("src/test/resources/simulate/sysml2/$eventsFile").absolutePath,
                        "--out",
                        out.absolutePath,
                    ) + extra,
                )
            val entries = if (result.statusCode == 0) loadTrace(out).entries else emptyList()
            return Triple(result, out, entries)
        }

        test("time events +4s +1s fire the after timer; a blocked prose guard keeps the machine in Green") {
            val (result, out, entries) = runTimers("timers.events.json")
            try {
                result.statusCode shouldBe 0
                entries.filterIsInstance<TraceEntry.ClockStarted>().single().startTime shouldBe "2000-01-01T00:00:00Z"
                entries.filterIsInstance<TraceEntry.TimerFired>().single().transitionId shouldBe "redToGreen"
                entries.filterIsInstance<TraceEntry.GuardNotEvaluable>().single().transitionId shouldBe "greenToDone"
                entries.none { it is TraceEntry.Terminated } shouldBe true
            } finally {
                out.delete()
            }
        }

        test("--prose-guards allow lets the prose guard pass") {
            val (result, out, entries) = runTimers("timers.events.json", "--prose-guards", "allow")
            try {
                result.statusCode shouldBe 0
                entries.filterIsInstance<TraceEntry.Terminated>().single().finalVertexId shouldBe "Done"
            } finally {
                out.delete()
            }
        }

        test("+next jumps to the next timer and --start-time sets the virtual clock") {
            val (result, out, entries) = runTimers("timers-next.events.json", "--start-time", "2026-05-01T12:00:00Z")
            try {
                result.statusCode shouldBe 0
                entries.filterIsInstance<TraceEntry.ClockStarted>().single().startTime shouldBe "2026-05-01T12:00:00Z"
                entries.filterIsInstance<TraceEntry.TimerFired>().single().dueTime shouldBe "2026-05-01T12:00:05Z"
            } finally {
                out.delete()
            }
        }

        test("an invalid time event is reported and not silently ignored") {
            val (result, out, _) = runTimers("timers-bad.events.json")
            out.delete()
            result.statusCode shouldBe ExitCodes.USAGE
        }

        test("--epoch-clock together with --start-time is rejected") {
            val (result, out, _) = runTimers("timers.events.json", "--epoch-clock", "--start-time", "2026-05-01T12:00:00Z")
            out.delete()
            result.statusCode shouldBe ExitCodes.USAGE
        }

        test("--start-time must be a UTC instant") {
            val (result, out, _) = runTimers("timers.events.json", "--start-time", "2026-05-01T12:00:00+02:00")
            out.delete()
            (result.statusCode != 0) shouldBe true
        }
    })
