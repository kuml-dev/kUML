package dev.kuml.cli.showcase

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.loadTrace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files

/**
 * V2.0.29 — End-to-end smoke tests for the Keysight Car2x V2X intersection scenario.
 *
 * Six tests validate all three event paths and render/validate flows:
 *  1. Happy path: simulate → Idle (post-reset)
 *  2. Conflict path: simulate → Idle (back from Negotiating)
 *  3. Late detection: guard fails → stays in Idle
 *  4. Render: `kuml render` produces valid SVG
 *  5. Script compiles without errors (`kuml validate` exits 0)
 *  6. All three event files are valid JSON and loadable
 */
class Car2xSmokeTest :
    FunSpec({

        val stmScript = File("src/test/resources/simulate/sysml2/car2x/car2x-scenario-stm.kuml.kts")
        val happyEvents = File("src/test/resources/simulate/sysml2/car2x/car2x-events-happy.json")
        val conflictEvents = File("src/test/resources/simulate/sysml2/car2x/car2x-events-conflict.json")
        val lateEvents = File("src/test/resources/simulate/sysml2/car2x/car2x-events-late-detection.json")

        // ── 1. Happy path: full cycle Idle → Approaching → Negotiating → Crossing → Departed → Idle ──

        test("Car2x happy path: state sequence visits Approaching, Negotiating, Crossing, Departed") {
            val out = Files.createTempFile("car2x-happy-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${stmScript.absolutePath} ${happyEvents.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0
                out.exists() shouldBe true
                (out.length() > 0) shouldBe true

                val stateSequence =
                    loadTrace(out)
                        .entries
                        .filterIsInstance<TraceEntry.StateEntered>()
                        .map { it.vertexId }

                // Happy path visits all five meaningful states in order:
                // Idle (initial auto) → Approaching → Negotiating → Crossing → Departed → Idle (reset)
                stateSequence shouldContainInOrder
                    listOf("Approaching", "Negotiating", "Crossing", "Departed", "Idle")
            } finally {
                out.delete()
            }
        }

        // ── 2. Conflict path: Negotiating → conflict → Idle ──────────────────────────────────────────

        test("Car2x conflict path: state sequence visits Negotiating then returns to Idle") {
            val out = Files.createTempFile("car2x-conflict-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${stmScript.absolutePath} ${conflictEvents.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0

                val stateSequence =
                    loadTrace(out)
                        .entries
                        .filterIsInstance<TraceEntry.StateEntered>()
                        .map { it.vertexId }

                // Conflict path: Idle → Approaching → Negotiating → Idle (conflict yield)
                stateSequence shouldContainInOrder listOf("Approaching", "Negotiating", "Idle")

                // Must NOT visit Crossing or Departed
                stateSequence.none { it == "Crossing" } shouldBe true
                stateSequence.none { it == "Departed" } shouldBe true
            } finally {
                out.delete()
            }
        }

        // ── 3. Late detection: guard fails (ttc=5 ≤ 10), machine stays in Idle ────────────────────────

        test("Car2x late detection: guard fails (ttc=5), machine stays in Idle") {
            val out = Files.createTempFile("car2x-late-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${stmScript.absolutePath} ${lateEvents.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0

                val stateSequence =
                    loadTrace(out)
                        .entries
                        .filterIsInstance<TraceEntry.StateEntered>()
                        .map { it.vertexId }

                // Only the initial auto-entry into Idle should appear; no transition away from it.
                stateSequence.none { it == "Approaching" } shouldBe true
                stateSequence.none { it == "Negotiating" } shouldBe true
                stateSequence.none { it == "Crossing" } shouldBe true
                stateSequence.none { it == "Departed" } shouldBe true

                // Guard was evaluated and failed — must appear in trace as GuardEvaluated(result=false)
                val guardResults =
                    loadTrace(out)
                        .entries
                        .filterIsInstance<TraceEntry.GuardEvaluated>()
                        .filter { !it.result }

                guardResults.isNotEmpty() shouldBe true
            } finally {
                out.delete()
            }
        }

        // ── 4. Render: kuml render produces a valid SVG file ─────────────────────────────────────────

        test("Car2x render: kuml render --format svg produces a non-empty SVG file") {
            val out = Files.createTempFile("car2x-render-", ".svg").toFile()
            try {
                val result =
                    KumlCli().test(
                        "render ${stmScript.absolutePath} --format svg --output ${out.absolutePath}",
                    )
                result.statusCode shouldBe 0
                out.exists() shouldBe true
                (out.length() > 0) shouldBe true

                val svgContent = out.readText()
                // A well-formed SVG begins with an xml declaration or svg element
                svgContent shouldContain "<svg"
                svgContent shouldContain "</svg>"
            } finally {
                out.delete()
            }
        }

        // ── 5. Script compiles: kuml validate exits 0 ────────────────────────────────────────────────

        test("Car2x script: kuml validate exits 0 (no compilation errors)") {
            val result =
                KumlCli().test(
                    "validate ${stmScript.absolutePath}",
                )
            result.statusCode shouldBe 0
        }

        // ── 6. All event files are valid JSON and parseable ───────────────────────────────────────────

        test("Car2x event files: all three JSON files are valid and loadable") {
            val json = Json { ignoreUnknownKeys = true }

            for (eventFile in listOf(happyEvents, conflictEvents, lateEvents)) {
                eventFile.exists() shouldBe true
                val root = json.parseToJsonElement(eventFile.readText()).jsonObject
                // Must have "events" array
                val events = root["events"]?.jsonArray
                events shouldNotBe null
                events!!.isEmpty() shouldBe false
                // Each event entry must have a "name" field
                events.forEach { entry ->
                    val name = entry.jsonObject["name"]
                    name shouldNotBe null
                }
            }

            // Spot-check happy events sequence length
            val happyRoot = json.parseToJsonElement(happyEvents.readText()).jsonObject
            val happyEventsArray = happyRoot["events"]!!.jsonArray
            happyEventsArray.size shouldBe 5

            // Spot-check conflict path length
            val conflictRoot = json.parseToJsonElement(conflictEvents.readText()).jsonObject
            val conflictEventsArray = conflictRoot["events"]!!.jsonArray
            conflictEventsArray.size shouldBe 3

            // Spot-check late detection length
            val lateRoot = json.parseToJsonElement(lateEvents.readText()).jsonObject
            val lateEventsArray = lateRoot["events"]!!.jsonArray
            lateEventsArray.size shouldBe 1
        }
    })
