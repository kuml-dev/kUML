package dev.kuml.runtime.trace

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject

class EventsFromTraceTest :
    FunSpec({

        val emptyPayload = JsonObject(emptyMap())

        test("extracts events from EventReceived entries") {
            val entries =
                listOf(
                    TraceEntry.EventReceived(seqNo = 0L, timestamp = "t0", eventName = "go", payload = emptyPayload),
                    TraceEntry.EventReceived(seqNo = 2L, timestamp = "t2", eventName = "done", payload = emptyPayload),
                )
            val events = EventsFromTrace.extract(entries)
            events shouldHaveSize 2
            events[0].name shouldBe "go"
            events[1].name shouldBe "done"
        }

        test("ignores entries with empty event name (synthetic)") {
            val entries =
                listOf(
                    TraceEntry.EventReceived(seqNo = 0L, timestamp = "t0", eventName = "", payload = emptyPayload),
                    TraceEntry.EventReceived(seqNo = 1L, timestamp = "t1", eventName = "confirm", payload = emptyPayload),
                )
            val events = EventsFromTrace.extract(entries)
            events shouldHaveSize 1
            events[0].name shouldBe "confirm"
        }

        test("ignores non-EventReceived entries") {
            val entries =
                listOf(
                    TraceEntry.StateEntered(seqNo = 0L, timestamp = "t0", vertexId = "A"),
                    TraceEntry.EventReceived(seqNo = 1L, timestamp = "t1", eventName = "go", payload = emptyPayload),
                    TraceEntry.TransitionFired(seqNo = 2L, timestamp = "t2", transitionId = "t1", fromVertexId = "A", toVertexId = "B"),
                    TraceEntry.StateExited(seqNo = 3L, timestamp = "t3", vertexId = "A"),
                    TraceEntry.Terminated(seqNo = 4L, timestamp = "t4", finalVertexId = "final"),
                )
            val events = EventsFromTrace.extract(entries)
            events shouldHaveSize 1
            events[0].name shouldBe "go"
        }

        test("respects seqNo ordering even if list is unordered") {
            val entries =
                listOf(
                    TraceEntry.EventReceived(seqNo = 5L, timestamp = "t5", eventName = "second", payload = emptyPayload),
                    TraceEntry.EventReceived(seqNo = 1L, timestamp = "t1", eventName = "first", payload = emptyPayload),
                )
            val events = EventsFromTrace.extract(entries)
            events shouldHaveSize 2
            events[0].name shouldBe "first"
            events[1].name shouldBe "second"
        }

        test("returns empty list for trace with no EventReceived entries") {
            val entries =
                listOf(
                    TraceEntry.StateEntered(seqNo = 0L, timestamp = "t0", vertexId = "A"),
                    TraceEntry.Terminated(seqNo = 1L, timestamp = "t1", finalVertexId = "final"),
                )
            val events = EventsFromTrace.extract(entries)
            events.shouldBeEmpty()
        }

        test("extract from TraceFile delegates to extract(entries)") {
            val traceFile =
                TraceFile(
                    modelId = "M",
                    entries =
                        listOf(
                            TraceEntry.EventReceived(seqNo = 0L, timestamp = "t0", eventName = "ping", payload = emptyPayload),
                        ),
                )
            val events = EventsFromTrace.extract(traceFile)
            events shouldHaveSize 1
            events[0].name shouldBe "ping"
        }
    })
