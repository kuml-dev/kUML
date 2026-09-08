package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.loadTrace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * ADR-0015 — CLI smoke test proving `kuml simulate` executes a UML Activity
 * diagram (`activityDiagram { … }`) via `dev.kuml.runtime.tokenflow.TokenFlowEngine`.
 * Before this wave, `kuml simulate` rejected such scripts with "Script must
 * produce exactly one UmlStateMachine".
 */
class SimulateCommandUmlActivityTest :
    FunSpec({
        val script = File("src/test/resources/simulate/uml/activity-decision.kuml.kts")
        val events = File("src/test/resources/simulate/uml/activity-decision.events.json")

        test("kuml simulate executes a UML Activity diagram with a Decision node") {
            val out = Files.createTempFile("kuml-simulate-uml-activity-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(listOf("simulate", script.absolutePath, events.absolutePath, "--out", out.absolutePath))
                result.statusCode shouldBe 0

                val trace = loadTrace(out)
                trace.entries.filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.body } shouldBe listOf("yes")
                trace.entries.filterIsInstance<TraceEntry.ActivityTerminated>().size shouldBe 1
            } finally {
                out.delete()
            }
        }
    })
