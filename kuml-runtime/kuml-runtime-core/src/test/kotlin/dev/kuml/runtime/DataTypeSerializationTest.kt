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
                    TraceEntry.EventReceived(0L, "t0", "confirm", JsonObject(emptyMap())),
                    TraceEntry.StateEntered(1L, "t1", "Draft"),
                    TraceEntry.ActionInvoked(
                        seqNo = 2L,
                        timestamp = "t2",
                        phase = ActionPhase.ENTRY,
                        action = "validate()",
                        vertexId = "Draft",
                        transitionId = null,
                    ),
                    TraceEntry.TransitionFired(3L, "t3", "tr1", "Draft", "Confirmed"),
                    TraceEntry.GuardEvaluated(4L, "t4", "tr1", "true", true),
                    TraceEntry.GuardWarning(5L, "t5", "tr1", "bad", "parse error"),
                    TraceEntry.ActionError(6L, "t6", "tr1", "boom"),
                    TraceEntry.StateExited(7L, "t7", "Draft"),
                    TraceEntry.Stayed(8L, "t8", "no match"),
                    TraceEntry.Terminated(9L, "t9", "Done"),
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
