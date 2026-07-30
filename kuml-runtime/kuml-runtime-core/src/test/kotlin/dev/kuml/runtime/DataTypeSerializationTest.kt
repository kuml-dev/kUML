package dev.kuml.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class DataTypeSerializationTest :
    FunSpec({

        val json =
            Json {
                prettyPrint = false
                classDiscriminator = "type"
                encodeDefaults = true
            }

        test("Event roundtrip JSON") {
            val original =
                Event(
                    name = "pay",
                    payload =
                        buildJsonObject {
                            put("amount", JsonPrimitive(150))
                            put("currency", JsonPrimitive("EUR"))
                        },
                    timestamp = "2026-06-05T09:30:00Z",
                    id = "evt-1",
                )
            val encoded = json.encodeToString(Event.serializer(), original)
            val decoded = json.decodeFromString(Event.serializer(), encoded)
            decoded shouldBe original
        }

        test("Event default payload is empty JsonObject") {
            Event.of("confirm").payload shouldBe JsonObject(emptyMap())
        }

        test("TraceEntry sealed list roundtrips bit-identical") {
            val list: List<TraceEntry> =
                listOf(
                    TraceEntry.EventReceived(seqNo = 0L, timestamp = "t0", eventName = "confirm", payload = JsonObject(emptyMap())),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "t1", vertexId = "Draft"),
                    TraceEntry.ActionInvoked(
                        seqNo = 2L,
                        timestamp = "t2",
                        phase = ActionPhase.ENTRY,
                        action = "validate()",
                        vertexId = "Draft",
                        transitionId = null,
                    ),
                    TraceEntry.TransitionFired(
                        seqNo = 3L,
                        timestamp = "t3",
                        transitionId = "tr1",
                        fromVertexId = "Draft",
                        toVertexId = "Confirmed",
                    ),
                    TraceEntry.GuardEvaluated(seqNo = 4L, timestamp = "t4", transitionId = "tr1", guard = "true", result = true),
                    TraceEntry.GuardWarning(seqNo = 5L, timestamp = "t5", transitionId = "tr1", guard = "bad", message = "parse error"),
                    TraceEntry.ActionError(seqNo = 6L, timestamp = "t6", transitionId = "tr1", message = "boom"),
                    TraceEntry.StateExited(seqNo = 7L, timestamp = "t7", vertexId = "Draft"),
                    TraceEntry.Stayed(seqNo = 8L, timestamp = "t8", reason = "no match"),
                    TraceEntry.Terminated(seqNo = 9L, timestamp = "t9", finalVertexId = "Done"),
                )
            val serializer = kotlinx.serialization.builtins.ListSerializer(TraceEntry.serializer())
            val encoded = json.encodeToString(serializer, list)
            val decoded = json.decodeFromString(serializer, encoded)
            decoded shouldBe list
        }

        test("Snapshot roundtrip JSON") {
            val s =
                Snapshot(
                    currentVertexIds = listOf("Draft", "Confirmed"),
                    variables = mapOf("x" to JsonPrimitive(1), "name" to JsonPrimitive("alice")),
                    traceSeqNo = 42L,
                )
            val encoded = json.encodeToString(Snapshot.serializer(), s)
            val decoded = json.decodeFromString(Snapshot.serializer(), encoded)
            decoded shouldBe s
        }

        test("ActionPhase enum serializes to expected names") {
            for (phase in ActionPhase.entries) {
                val encoded = json.encodeToString(ActionPhase.serializer(), phase)
                encoded shouldContain phase.name
            }
        }

        test("Deserialization fails when required TraceEntry fields are missing") {
            val malformed = """{"type":"dev.kuml.runtime.TraceEntry.StateEntered"}"""
            shouldThrow<Exception> {
                json.decodeFromString(TraceEntry.serializer(), malformed)
            }
        }
    })
