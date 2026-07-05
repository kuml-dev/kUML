package dev.kuml.cli.trace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlCli
import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import java.io.File
import java.nio.file.Files

class TraceReplayCommandTest :
    FunSpec({

        val script = File("src/test/resources/simulate/order-lifecycle.kuml.kts")
        val events = File("src/test/resources/simulate/order-lifecycle.events.json")
        val actScript = File("src/test/resources/simulate/sysml2/simple-act.kuml.kts")
        val actEvents = File("src/test/resources/simulate/sysml2/simple-act.events.json")

        /** Generate an STM trace file from the order-lifecycle model. */
        fun generateStmTrace(): File {
            val traceOut = Files.createTempFile("kuml-trace-", ".json").toFile()
            KumlCli().test(
                listOf(
                    "simulate",
                    script.absolutePath,
                    events.absolutePath,
                    "--out",
                    traceOut.absolutePath,
                    "--epoch-clock",
                ),
            )
            return traceOut
        }

        /** Generate an Activity trace file from the simple-act model. */
        fun generateActTrace(): File {
            val traceOut = Files.createTempFile("kuml-act-trace-", ".json").toFile()
            KumlCli().test(
                listOf("simulate", actScript.absolutePath, actEvents.absolutePath, "--out", traceOut.absolutePath),
            )
            return traceOut
        }

        // ── STM tests (existing) ──────────────────────────────────────────────

        test("exit 0 when replayed trace matches original") {
            val traceFile = generateStmTrace()
            try {
                val result =
                    KumlCli().test(
                        listOf("trace", "replay", traceFile.absolutePath, script.absolutePath),
                    )
                result.statusCode shouldBe 0
            } finally {
                traceFile.delete()
            }
        }

        test("exit TRACE_REPLAY_MISMATCH (8) when trace diverges from model") {
            // Create a trace with extra/wrong entries
            val divergentTrace = Files.createTempFile("kuml-divergent-", ".json").toFile()
            try {
                // Write a trace with only one fake entry — model would produce more
                val fakeTrace =
                    TraceFile(
                        modelId = "OrderLifecycle",
                        entries =
                            listOf(
                                TraceEntry.EventReceived(
                                    seqNo = 0L,
                                    timestamp = "1970-01-01T00:00:00Z",
                                    eventName = "confirm",
                                    payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                                ),
                            ),
                    )
                divergentTrace.writeText(KumlRuntimeJson.encodeToString(TraceFile.serializer(), fakeTrace))

                val result =
                    KumlCli().test(
                        listOf("trace", "replay", divergentTrace.absolutePath, script.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.TRACE_REPLAY_MISMATCH
            } finally {
                divergentTrace.delete()
            }
        }

        test("--verbose flag prints diff details on mismatch") {
            val divergentTrace = Files.createTempFile("kuml-divergent-verbose-", ".json").toFile()
            try {
                val fakeTrace =
                    TraceFile(
                        modelId = "OrderLifecycle",
                        entries =
                            listOf(
                                TraceEntry.EventReceived(
                                    seqNo = 0L,
                                    timestamp = "1970-01-01T00:00:00Z",
                                    eventName = "confirm",
                                    payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                                ),
                            ),
                    )
                divergentTrace.writeText(KumlRuntimeJson.encodeToString(TraceFile.serializer(), fakeTrace))

                val result =
                    KumlCli().test(
                        listOf("trace", "replay", divergentTrace.absolutePath, script.absolutePath, "--verbose"),
                    )
                result.statusCode shouldBe ExitCodes.TRACE_REPLAY_MISMATCH
                // With --verbose the output should contain diff details
                result.output.shouldNotBeEmpty()
            } finally {
                divergentTrace.delete()
            }
        }

        // ── New: Activity trace tests ─────────────────────────────────────────

        test("exit 0 when activity trace matches original (ACT script)") {
            val traceFile = generateActTrace()
            try {
                val result =
                    KumlCli().test(
                        listOf("trace", "replay", traceFile.absolutePath, actScript.absolutePath),
                    )
                result.statusCode shouldBe 0
            } finally {
                traceFile.delete()
            }
        }

        test("exit TRACE_REPLAY_MISMATCH (8) when activity trace has fewer entries than replay produces") {
            // A trace with only one TokenPlaced entry and no modelId — the real replay would produce many more
            val fakeActTrace =
                TraceFile(
                    modelId = null, // no model ID → skip mismatch check
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "init", clock = 0L),
                        ),
                )
            val fakeFile = Files.createTempFile("kuml-fake-act-", ".json").toFile()
            try {
                fakeFile.writeText(KumlRuntimeJson.encodeToString(TraceFile.serializer(), fakeActTrace))
                val result =
                    KumlCli().test(
                        listOf("trace", "replay", fakeFile.absolutePath, actScript.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.TRACE_REPLAY_MISMATCH
            } finally {
                fakeFile.delete()
            }
        }

        test("exit TRACE_UNSUPPORTED_FLAVOUR (9) for empty trace") {
            val emptyTrace = Files.createTempFile("kuml-empty-", ".json").toFile()
            try {
                val emptyTraceFile = TraceFile(modelId = null, entries = emptyList())
                emptyTrace.writeText(KumlRuntimeJson.encodeToString(TraceFile.serializer(), emptyTraceFile))

                val result =
                    KumlCli().test(
                        listOf("trace", "replay", emptyTrace.absolutePath, script.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.TRACE_UNSUPPORTED_FLAVOUR
            } finally {
                emptyTrace.delete()
            }
        }

        test("exit TRACE_UNSUPPORTED_FLAVOUR (9) for MIXED trace") {
            val mixedTrace = Files.createTempFile("kuml-mixed-", ".json").toFile()
            try {
                val mixedTraceFile =
                    TraceFile(
                        modelId = null,
                        entries =
                            listOf(
                                TraceEntry.StateEntered(seqNo = 0L, timestamp = "", vertexId = "A"),
                                TraceEntry.TokenPlaced(seqNo = 1L, timestamp = "", nodeId = "n1", clock = 0L),
                            ),
                    )
                mixedTrace.writeText(KumlRuntimeJson.encodeToString(TraceFile.serializer(), mixedTraceFile))

                val result =
                    KumlCli().test(
                        listOf("trace", "replay", mixedTrace.absolutePath, script.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.TRACE_UNSUPPORTED_FLAVOUR
            } finally {
                mixedTrace.delete()
            }
        }
    })
