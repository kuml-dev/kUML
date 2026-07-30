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
                            TraceEntry.EventReceived(seqNo = 0L, timestamp = "t0", eventName = "go", payload = JsonObject(emptyMap())),
                            TraceEntry.StateEntered(seqNo = 1L, timestamp = "t1", vertexId = "A"),
                            TraceEntry.TransitionFired(
                                seqNo = 2L,
                                timestamp = "t2",
                                transitionId = "tr0",
                                fromVertexId = "init",
                                toVertexId = "A",
                            ),
                            TraceEntry.GuardEvaluated(seqNo = 3L, timestamp = "t3", transitionId = "tr0", guard = "(null)", result = true),
                            TraceEntry.Terminated(seqNo = 4L, timestamp = "t4", finalVertexId = "Done"),
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
                        TraceEntry.EventReceived(seqNo = 0L, timestamp = "t", eventName = "confirm", payload = JsonObject(emptyMap())),
                        TraceEntry.StateEntered(seqNo = 1L, timestamp = "t", vertexId = "A"),
                    )
                val tf = tmpDir.resolve("trace.json")
                writeTrace(trace = tr, file = tf, modelId = "M")
                loadTrace(tf).entries shouldBe tr
                loadTrace(tf).modelId shouldBe "M"
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    })
