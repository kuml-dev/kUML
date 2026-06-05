package dev.kuml.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files

class EventFileTraceFileSerializationTest :
    FunSpec({

        test("EventFile JSON roundtrip") {
            val original =
                EventFile(
                    events =
                        listOf(
                            Event(name = "go"),
                            Event(
                                name = "pay",
                                payload = buildJsonObject { put("amount", JsonPrimitive(100)) },
                            ),
                        ),
                )
            val encoded = KumlRuntimeJson.encodeToString(EventFile.serializer(), original)
            val decoded = KumlRuntimeJson.decodeFromString(EventFile.serializer(), encoded)
            decoded shouldBe original
        }

        test("TraceFile JSON roundtrip with sealed entries (type discriminator)") {
            val original =
                TraceFile(
                    modelId = "sm-1",
                    entries =
                        listOf(
                            TraceEntry.EventReceived(0L, "t0", "go", JsonObject(emptyMap())),
                            TraceEntry.StateEntered(1L, "t1", "A"),
                            TraceEntry.TransitionFired(2L, "t2", "tr0", "init", "A"),
                            TraceEntry.GuardEvaluated(3L, "t3", "tr0", "(null)", true),
                            TraceEntry.Terminated(4L, "t4", "Done"),
                        ),
                )
            val encoded = KumlRuntimeJson.encodeToString(TraceFile.serializer(), original)
            // Type discriminator should be present
            (encoded.contains("\"type\"")) shouldBe true
            val decoded = KumlRuntimeJson.decodeFromString(TraceFile.serializer(), encoded)
            decoded shouldBe original
        }

        test("loadEvents rejects wrong schema") {
            val tmp = Files.createTempFile("kuml-events-", ".json").toFile()
            tmp.writeText("""{"schema":"kuml.events.v999","events":[]}""")
            shouldThrow<IllegalArgumentException> { loadEvents(tmp) }
            tmp.delete()
        }

        test("loadEvents / writeTrace / loadTrace roundtrip on disk") {
            val tmpDir = Files.createTempDirectory("kuml-trace-rt-").toFile()
            try {
                val eventsFile =
                    EventFile(events = listOf(Event(name = "confirm"), Event(name = "pay")))
                val ef = tmpDir.resolve("events.json")
                ef.writeText(KumlRuntimeJson.encodeToString(EventFile.serializer(), eventsFile))
                loadEvents(ef) shouldBe eventsFile.events

                val tr =
                    listOf<TraceEntry>(
                        TraceEntry.EventReceived(0L, "t", "confirm", JsonObject(emptyMap())),
                        TraceEntry.StateEntered(1L, "t", "A"),
                    )
                val tf = tmpDir.resolve("trace.json")
                writeTrace(tr, tf, modelId = "M")
                loadTrace(tf).entries shouldBe tr
                loadTrace(tf).modelId shouldBe "M"
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    })
