package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.loadTrace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * V2.0.18 — CLI smoke tests that prove `kuml simulate` accepts SysML 2 ACT
 * scripts and runs them through the [dev.kuml.runtime.activity.ActivityRuntime].
 */
class SimulateCommandActTest :
    FunSpec({

        val simpleScript = File("src/test/resources/simulate/sysml2/simple-act.kuml.kts")
        val simpleEvents = File("src/test/resources/simulate/sysml2/simple-act.events.json")
        val loopScript = File("src/test/resources/simulate/sysml2/loop-act.kuml.kts")
        val stmScript = File("src/test/resources/simulate/sysml2/traffic-light.kuml.kts")
        val stmEvents = File("src/test/resources/simulate/sysml2/traffic-light.events.json")

        // ── 1. Smoke test: simple ACT runs to ActivityTerminated ──────────────

        test("kuml simulate accepts a SysML 2 ACT script and produces ActivityTerminated trace") {
            val out = Files.createTempFile("kuml-simulate-act-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${simpleScript.absolutePath} ${simpleEvents.absolutePath} " +
                            "--out ${out.absolutePath}",
                    )

                result.statusCode shouldBe 0

                val trace = loadTrace(out).entries
                val terminated = trace.filterIsInstance<TraceEntry.ActivityTerminated>()
                terminated.size shouldBe 1

                val text = out.readText()
                text shouldContain "ActivityTerminated"
            } finally {
                out.delete()
            }
        }

        // ── 2. --max-steps causes early termination on long-running model ─────

        test("--max-steps 3 causes early termination on loop ACT model with non-zero exit") {
            val out = Files.createTempFile("kuml-simulate-loop-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${loopScript.absolutePath} ${simpleEvents.absolutePath} " +
                            "--max-steps 3 --out ${out.absolutePath}",
                    )

                // ActivityDeadlockException is caught and printed to System.err, then
                // ProgramResult(ExitCodes.SCRIPT_ERROR) is thrown. The exit code must be non-zero.
                result.statusCode shouldNotBe 0
            } finally {
                out.delete()
            }
        }

        // ── 3. STM script still works (regression guard) ──────────────────────

        test("STM script still works correctly after ACT branch addition") {
            val out = Files.createTempFile("kuml-simulate-stm-regression-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${stmScript.absolutePath} ${stmEvents.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )

                result.statusCode shouldBe 0
                val text = out.readText()
                text shouldContain "Terminated"
            } finally {
                out.delete()
            }
        }

        // ── 4. --out flag writes trace JSON ───────────────────────────────────

        test("--out flag writes trace JSON for ACT script") {
            val out = Files.createTempFile("kuml-simulate-out-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${simpleScript.absolutePath} ${simpleEvents.absolutePath} " +
                            "--out ${out.absolutePath}",
                    )

                result.statusCode shouldBe 0
                out.exists() shouldBe true
                (out.length() > 0) shouldBe true

                val trace = loadTrace(out)
                trace.entries.isNotEmpty() shouldBe true
                trace.modelId shouldBe "Simple Activity"
            } finally {
                out.delete()
            }
        }
    })
