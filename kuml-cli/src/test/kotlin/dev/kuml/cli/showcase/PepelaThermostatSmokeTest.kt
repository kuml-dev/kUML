package dev.kuml.cli.showcase

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.loadTrace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files

/**
 * V2.0.19 — End-to-end smoke tests for the Pepela Smart Home thermostat showcase.
 *
 * Six tests cover the state machine (STM) and activity diagram (ACT) paths of
 * the kUML Behaviour-Runtime, exercising:
 *  1. STM terminates without exception
 *  2. STM trace contains expected state sequence (Idle → Heating → Idle → Cooling → Eco)
 *  3. STM mismatch detection via --expected-trace (wrong trace → non-zero exit)
 *  4. ACT produces ActivityTerminated
 *  5. ACT trace contains expected nodes (Calibrate, UpdateDisplay, LogReady)
 *  6. STM determinism: two runs produce byte-identical traces
 */
class PepelaThermostatSmokeTest :
    FunSpec({

        val stmScript = File("src/test/resources/simulate/sysml2/pepela/thermostat-stm.kuml.kts")
        val stmEvents = File("src/test/resources/simulate/sysml2/pepela/thermostat-stm.events.json")
        val flowScript = File("src/test/resources/simulate/sysml2/pepela/thermostat-flow.kuml.kts")
        val flowEvents = File("src/test/resources/simulate/sysml2/pepela/thermostat-flow.events.json")

        // ── 1. STM: runs without exception ────────────────────────────────────

        test("Pepela STM: kuml simulate terminates without exception") {
            val out = Files.createTempFile("pepela-stm-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${stmScript.absolutePath} ${stmEvents.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0
                out.exists() shouldBe true
                (out.length() > 0) shouldBe true
            } finally {
                out.delete()
            }
        }

        // ── 2. STM: state sequence matches golden expected path ───────────────

        test("Pepela STM: state sequence visits Off, Idle, Heating, Idle, Cooling, Eco, Off") {
            val out = Files.createTempFile("pepela-stm-seq-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${stmScript.absolutePath} ${stmEvents.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0

                val stateSequence =
                    loadTrace(out)
                        .entries
                        .filterIsInstance<TraceEntry.StateEntered>()
                        .map { it.vertexId }

                // The initial-to-Off auto-fire happens during start() before any external
                // event, so Off appears first. The visible sequence is:
                // Off → Idle (powerOn) → Heating (tick 16<20) → Idle (tick 21>=21)
                // → Cooling (tick 24>22) → Idle (tick 21<=21) → Eco (ecoMode)
                // → Idle (normalMode) → Off (powerOff)
                stateSequence shouldContainInOrder
                    listOf("Off", "Idle", "Heating", "Idle", "Cooling", "Idle", "Eco", "Idle", "Off")

                // Also check that the trace is correctly written
                val text = out.readText()
                text shouldNotBe ""
            } finally {
                out.delete()
            }
        }

        // ── 3. STM: --expected-trace mismatch → non-zero exit ─────────────────

        test("Pepela STM: --expected-trace with wrong trace produces non-zero exit code") {
            val out = Files.createTempFile("pepela-stm-wrong-", ".trace.json").toFile()
            // The traffic-light trace has a completely different state sequence
            val wrongExpected = File("src/test/resources/simulate/sysml2/traffic-light.events.json")
            val expectedTraceFile = Files.createTempFile("pepela-wrong-expected-", ".trace.json").toFile()
            try {
                // First produce a trace from the traffic-light to use as a "wrong" expected
                KumlCli().test(
                    "simulate ${File("src/test/resources/simulate/sysml2/traffic-light.kuml.kts").absolutePath}" +
                        " ${wrongExpected.absolutePath} " +
                        "--out ${expectedTraceFile.absolutePath} --epoch-clock",
                )

                // Now compare our thermostat trace against that incompatible expected trace
                val result =
                    KumlCli().test(
                        "simulate ${stmScript.absolutePath} ${stmEvents.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock " +
                            "--expected ${expectedTraceFile.absolutePath}",
                    )
                // Should detect mismatch and return non-zero
                result.statusCode shouldNotBe 0
            } finally {
                out.delete()
                expectedTraceFile.delete()
            }
        }

        // ── 4. ACT: produces ActivityTerminated ───────────────────────────────

        test("Pepela ACT: boot calibration activity reaches ActivityTerminated") {
            val out = Files.createTempFile("pepela-flow-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${flowScript.absolutePath} ${flowEvents.absolutePath} " +
                            "--out ${out.absolutePath}",
                    )
                result.statusCode shouldBe 0

                val trace = loadTrace(out).entries
                val terminated = trace.filterIsInstance<TraceEntry.ActivityTerminated>()
                terminated.size shouldBe 1
            } finally {
                out.delete()
            }
        }

        // ── 5. ACT: trace contains Calibrate, UpdateDisplay, LogReady ─────────

        test("Pepela ACT: trace visits Calibrate, UpdateDisplay, and LogReady on happy path") {
            val out = Files.createTempFile("pepela-flow-nodes-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${flowScript.absolutePath} ${flowEvents.absolutePath} " +
                            "--out ${out.absolutePath}",
                    )
                result.statusCode shouldBe 0

                val invokedNodes =
                    loadTrace(out)
                        .entries
                        .filterIsInstance<TraceEntry.ActivityActionInvoked>()
                        .map { it.nodeId }

                invokedNodes shouldContain "Calibrate"
                invokedNodes shouldContain "UpdateDisplay"
                invokedNodes shouldContain "LogReady"
                invokedNodes shouldContain "ReadSensors"
            } finally {
                out.delete()
            }
        }

        // ── 6. STM determinism: two identical runs produce identical traces ────

        test("Pepela STM: two runs with --epoch-clock produce byte-identical trace files") {
            val out1 = Files.createTempFile("pepela-det-1-", ".trace.json").toFile()
            val out2 = Files.createTempFile("pepela-det-2-", ".trace.json").toFile()
            try {
                KumlCli().test(
                    "simulate ${stmScript.absolutePath} ${stmEvents.absolutePath} " +
                        "--out ${out1.absolutePath} --epoch-clock",
                )
                KumlCli().test(
                    "simulate ${stmScript.absolutePath} ${stmEvents.absolutePath} " +
                        "--out ${out2.absolutePath} --epoch-clock",
                )
                out1.readText() shouldBe out2.readText()
            } finally {
                out1.delete()
                out2.delete()
            }
        }
    })
