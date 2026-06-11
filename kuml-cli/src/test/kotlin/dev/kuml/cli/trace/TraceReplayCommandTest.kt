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

        /** Generate a trace file from the order-lifecycle model. */
        fun generateTrace(): File {
            val traceOut = Files.createTempFile("kuml-trace-", ".json").toFile()
            KumlCli().test(
                "simulate ${script.absolutePath} ${events.absolutePath} " +
                    "--out ${traceOut.absolutePath} --epoch-clock",
            )
            return traceOut
        }

        test("exit 0 when replayed trace matches original") {
            val traceFile = generateTrace()
            try {
                val result =
                    KumlCli().test(
                        "trace replay ${traceFile.absolutePath} ${script.absolutePath}",
                    )
                result.statusCode shouldBe 0
            } finally {
                traceFile.delete()
            }
        }

        test("exit TRACE_REPLAY_MISMATCH (7) when trace diverges from model") {
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
                        "trace replay ${divergentTrace.absolutePath} ${script.absolutePath}",
                    )
                result.statusCode shouldBe ExitCodes.TRACE_REPLAY_MISMATCH
            } finally {
                divergentTrace.delete()
            }
        }

        test("exit TRACE_UNSUPPORTED_FLAVOUR (8) when given an activity trace") {
            val activityTrace = Files.createTempFile("kuml-activity-", ".json").toFile()
            try {
                // Write an activity-flavoured trace (contains TokenPlaced)
                val fakeActTrace =
                    TraceFile(
                        modelId = "ActivityModel",
                        entries =
                            listOf(
                                TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "n1", clock = 0L),
                                TraceEntry.ActivityTerminated(seqNo = 1L, timestamp = "", clock = 1L),
                            ),
                    )
                activityTrace.writeText(KumlRuntimeJson.encodeToString(TraceFile.serializer(), fakeActTrace))

                val result =
                    KumlCli().test(
                        "trace replay ${activityTrace.absolutePath} ${script.absolutePath}",
                    )
                result.statusCode shouldBe ExitCodes.TRACE_UNSUPPORTED_FLAVOUR
            } finally {
                activityTrace.delete()
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
                        "trace replay ${divergentTrace.absolutePath} ${script.absolutePath} --verbose",
                    )
                result.statusCode shouldBe ExitCodes.TRACE_REPLAY_MISMATCH
                // With --verbose the output should contain diff details
                result.output.shouldNotBeEmpty()
            } finally {
                divergentTrace.delete()
            }
        }
    })
