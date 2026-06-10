package dev.kuml.cli.run

import dev.kuml.runtime.snapshot.MigrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class BatchAdapterTest :
    FunSpec({

        val trafficLightScript = File("src/test/resources/simulate/sysml2/traffic-light.kuml.kts")
        val trafficLightEvents = File("src/test/resources/simulate/sysml2/traffic-light.events.json")
        val actScript = File("src/test/resources/simulate/sysml2/simple-act.kuml.kts")
        val actEvents = File("src/test/resources/simulate/sysml2/simple-act.events.json")

        // ── 1. Batch runs traffic-light events to completion ──────────────────

        test("batch runs traffic-light events to completion") {
            val manager = RunSessionManager()
            val startResult =
                manager.start(
                    scriptText = trafficLightScript.readText(),
                    scriptName = trafficLightScript.name,
                    restoreFrom = null,
                    migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
                )
            startResult is SessionResult.Ok || startResult is SessionResult.Terminated

            val adapter = BatchAdapter(manager, trafficLightEvents, null)
            val exitCode = adapter.run()
            exitCode shouldBe 0
        }

        // ── 2. Batch with ACT script terminates ───────────────────────────────

        test("batch with ACT script terminates") {
            val manager = RunSessionManager()
            manager.start(
                scriptText = actScript.readText(),
                scriptName = actScript.name,
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            // ACT terminates on its own via step — we just provide some events
            val adapter = BatchAdapter(manager, actEvents, null)
            val exitCode = adapter.run()
            exitCode shouldBe 0
        }

        // ── 3. Batch writes trace to output file ──────────────────────────────

        test("batch writes trace to output file") {
            val traceOut = Files.createTempFile("kuml-batch-trace-", ".json")
            try {
                val manager = RunSessionManager()
                manager.start(
                    scriptText = trafficLightScript.readText(),
                    scriptName = trafficLightScript.name,
                    restoreFrom = null,
                    migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
                )
                val adapter = BatchAdapter(manager, trafficLightEvents, traceOut)
                val exitCode = adapter.run()
                exitCode shouldBe 0
                val content = traceOut.toFile().readText()
                // TraceFile uses encodeDefaults=false so "schema" field is omitted;
                // verify structure by checking for modelId and entries fields instead
                content shouldContain "modelId"
                content shouldContain "entries"
            } finally {
                traceOut.toFile().delete()
            }
        }
    })
