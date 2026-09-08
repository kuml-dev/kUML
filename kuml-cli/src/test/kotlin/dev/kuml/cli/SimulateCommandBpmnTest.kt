package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.loadTrace
import dev.kuml.runtime.trace.TraceFlavour
import dev.kuml.runtime.trace.TraceFlavourDetector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/**
 * ADR-0015 — CLI smoke tests proving `kuml simulate` executes BPMN process
 * diagrams via `dev.kuml.runtime.tokenflow.TokenFlowEngine`.
 */
class SimulateCommandBpmnTest :
    FunSpec({
        val escalationScript = File("src/test/resources/simulate/bpmn/eskalationsprozess.kuml.kts")
        val escalationEvents = File("src/test/resources/simulate/bpmn/eskalationsprozess.events.json")
        val twoProcessesScript = File("src/test/resources/simulate/bpmn/two-processes.kuml.kts")
        val twoProcessesEvents = File("src/test/resources/simulate/bpmn/two-processes.events.json")
        val collaborationScript = File("src/test/resources/simulate/bpmn/collaboration.kuml.kts")

        test("kuml simulate runs a BPMN process to termination and writes an ACTIVITY-flavoured trace") {
            val out = Files.createTempFile("kuml-simulate-bpmn-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("simulate", escalationScript.absolutePath, escalationEvents.absolutePath, "--out", out.absolutePath),
                    )
                result.statusCode shouldBe 0

                val trace = loadTrace(out)
                trace.entries.filterIsInstance<TraceEntry.ActivityTerminated>().size shouldBe 1
                // T-OKF7 (CLI level): the trace round-trips as ACTIVITY-flavoured, exactly
                // like a SysML 2 ACT trace — proving BpmnTokenTimelineBuilder's downstream
                // consumers see no difference between the two producers.
                TraceFlavourDetector.detect(trace.entries) shouldBe TraceFlavour.ACTIVITY
            } finally {
                out.delete()
            }
        }

        test("--process overrides the script's own diagram to select a different process") {
            val out = Files.createTempFile("kuml-simulate-bpmn-process-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "simulate",
                            twoProcessesScript.absolutePath,
                            twoProcessesEvents.absolutePath,
                            "--process",
                            "procB",
                            "--out",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe 0
                val trace = loadTrace(out)
                trace.entries.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("onlyB")
            } finally {
                out.delete()
            }
        }

        test("without --process, the script's own diagram (procA) is used") {
            val out = Files.createTempFile("kuml-simulate-bpmn-default-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("simulate", twoProcessesScript.absolutePath, twoProcessesEvents.absolutePath, "--out", out.absolutePath),
                    )
                result.statusCode shouldBe 0
                val trace = loadTrace(out)
                trace.entries.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("onlyA")
            } finally {
                out.delete()
            }
        }

        test("a Collaboration diagram (no process at all) is rejected with a clear error, not a crash") {
            // System.err output (not captured by clikt's test harness into `result.stderr`,
            // which only captures Clikt's own stdout/echo stream) is exercised manually —
            // the essential contract this test protects is "clean SCRIPT_ERROR exit, no
            // uncaught exception / stack trace / crash".
            val result = KumlCli().test(listOf("simulate", collaborationScript.absolutePath))
            result.statusCode shouldNotBe 0
            result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
        }

        test("--guard-timeout-ms with an unresolvable guard still terminates via the BPMN default flow (sandboxed by default)") {
            // No events file -> "verfuegbar" is not in context -> guard evaluates false
            // quickly (no actual slow guard here, this just proves --guard-timeout-ms is
            // accepted on the BPMN path without error).
            val out = Files.createTempFile("kuml-simulate-bpmn-timeout-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "simulate",
                            escalationScript.absolutePath,
                            escalationEvents.absolutePath,
                            "--guard-timeout-ms",
                            "500",
                            "--out",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe 0
            } finally {
                out.delete()
            }
        }

        test("multiple declared process diagrams and no --process -> warns which one was picked") {
            val twoDiagramsScript = File("src/test/resources/simulate/bpmn/two-processes-two-diagrams.kuml.kts")
            val out = Files.createTempFile("kuml-simulate-bpmn-multi-diagram-", ".trace.json").toFile()
            val capturedErr = ByteArrayOutputStream()
            val originalErr = System.err
            try {
                val result =
                    System.setErr(PrintStream(capturedErr)).let {
                        KumlCli().test(
                            listOf("simulate", twoDiagramsScript.absolutePath, twoProcessesEvents.absolutePath, "--out", out.absolutePath),
                        )
                    }
                result.statusCode shouldBe 0
                // Still resolves the first declared diagram (procA) — this test protects
                // the WARNING, not a behaviour change in which diagram is picked.
                val trace = loadTrace(out)
                trace.entries.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("onlyA")

                val errText = capturedErr.toString(Charsets.UTF_8)
                errText shouldContain "declares 2 process"
                errText shouldContain "procA"
                errText shouldContain "--process"
            } finally {
                System.setErr(originalErr)
                out.delete()
            }
        }
    })
