package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject

class TraceDiffTest :
    FunSpec({

        test("equal traces match even when timestamps differ") {
            val a =
                listOf<TraceEntry>(
                    TraceEntry.EventReceived(seqNo = 0L, timestamp = "ts-A", eventName = "go", payload = JsonObject(emptyMap())),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "ts-A", vertexId = "A"),
                )
            val b =
                listOf<TraceEntry>(
                    TraceEntry.EventReceived(seqNo = 0L, timestamp = "ts-B", eventName = "go", payload = JsonObject(emptyMap())),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "ts-B", vertexId = "A"),
                )
            val r = TraceDiff.compare(actual = a, expected = b)
            r.isMatch shouldBe true
            r.matched shouldBe 2
        }

        test("diff at position N reports ValueDiffer") {
            val a =
                listOf<TraceEntry>(
                    TraceEntry.EventReceived(seqNo = 0L, timestamp = "t", eventName = "go", payload = JsonObject(emptyMap())),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "t", vertexId = "X"),
                )
            val b =
                listOf<TraceEntry>(
                    TraceEntry.EventReceived(seqNo = 0L, timestamp = "t", eventName = "go", payload = JsonObject(emptyMap())),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "t", vertexId = "Y"),
                )
            val r = TraceDiff.compare(actual = a, expected = b)
            r.isMatch shouldBe false
            r.mismatches.size shouldBe 1
            (r.mismatches.first() is TraceDiff.Mismatch.ValueDiffer) shouldBe true
        }

        test("extra actual entries reported") {
            val a =
                listOf<TraceEntry>(
                    TraceEntry.StateEntered(seqNo = 0L, timestamp = "t", vertexId = "A"),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "t", vertexId = "B"),
                )
            val b = listOf<TraceEntry>(TraceEntry.StateEntered(seqNo = 0L, timestamp = "t", vertexId = "A"))
            val r = TraceDiff.compare(actual = a, expected = b)
            r.mismatches.size shouldBe 1
            (r.mismatches.first() is TraceDiff.Mismatch.ExtraActual) shouldBe true
        }

        test("missing expected entries reported") {
            val a = listOf<TraceEntry>(TraceEntry.StateEntered(seqNo = 0L, timestamp = "t", vertexId = "A"))
            val b =
                listOf<TraceEntry>(
                    TraceEntry.StateEntered(seqNo = 0L, timestamp = "t", vertexId = "A"),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "t", vertexId = "B"),
                )
            val r = TraceDiff.compare(actual = a, expected = b)
            r.mismatches.size shouldBe 1
            (r.mismatches.first() is TraceDiff.Mismatch.MissingExpected) shouldBe true
        }

        test("toHumanReadable contains diff markers for mismatches") {
            val a = listOf<TraceEntry>(TraceEntry.StateEntered(seqNo = 0L, timestamp = "t", vertexId = "X"))
            val b = listOf<TraceEntry>(TraceEntry.StateEntered(seqNo = 0L, timestamp = "t", vertexId = "Y"))
            val r = TraceDiff.compare(actual = a, expected = b)
            val text = r.toHumanReadable()
            text shouldContain "expected:"
            text shouldContain "actual:"
        }

        test("toHumanReadable says 'match' on equal traces") {
            val a = listOf<TraceEntry>(TraceEntry.StateEntered(seqNo = 0L, timestamp = "t", vertexId = "A"))
            val r = TraceDiff.compare(actual = a, expected = a)
            r.toHumanReadable() shouldContain "match"
        }
    })
