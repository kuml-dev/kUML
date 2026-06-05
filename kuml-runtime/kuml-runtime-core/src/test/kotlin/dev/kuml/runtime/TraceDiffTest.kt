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
                    TraceEntry.EventReceived(0L, "ts-A", "go", JsonObject(emptyMap())),
                    TraceEntry.StateEntered(1L, "ts-A", "A"),
                )
            val b =
                listOf<TraceEntry>(
                    TraceEntry.EventReceived(0L, "ts-B", "go", JsonObject(emptyMap())),
                    TraceEntry.StateEntered(1L, "ts-B", "A"),
                )
            val r = TraceDiff.compare(a, b)
            r.isMatch shouldBe true
            r.matched shouldBe 2
        }

        test("diff at position N reports ValueDiffer") {
            val a =
                listOf<TraceEntry>(
                    TraceEntry.EventReceived(0L, "t", "go", JsonObject(emptyMap())),
                    TraceEntry.StateEntered(1L, "t", "X"),
                )
            val b =
                listOf<TraceEntry>(
                    TraceEntry.EventReceived(0L, "t", "go", JsonObject(emptyMap())),
                    TraceEntry.StateEntered(1L, "t", "Y"),
                )
            val r = TraceDiff.compare(a, b)
            r.isMatch shouldBe false
            r.mismatches.size shouldBe 1
            (r.mismatches.first() is TraceDiff.Mismatch.ValueDiffer) shouldBe true
        }

        test("extra actual entries reported") {
            val a =
                listOf<TraceEntry>(
                    TraceEntry.StateEntered(0L, "t", "A"),
                    TraceEntry.StateEntered(1L, "t", "B"),
                )
            val b = listOf<TraceEntry>(TraceEntry.StateEntered(0L, "t", "A"))
            val r = TraceDiff.compare(a, b)
            r.mismatches.size shouldBe 1
            (r.mismatches.first() is TraceDiff.Mismatch.ExtraActual) shouldBe true
        }

        test("missing expected entries reported") {
            val a = listOf<TraceEntry>(TraceEntry.StateEntered(0L, "t", "A"))
            val b =
                listOf<TraceEntry>(
                    TraceEntry.StateEntered(0L, "t", "A"),
                    TraceEntry.StateEntered(1L, "t", "B"),
                )
            val r = TraceDiff.compare(a, b)
            r.mismatches.size shouldBe 1
            (r.mismatches.first() is TraceDiff.Mismatch.MissingExpected) shouldBe true
        }

        test("toHumanReadable contains diff markers for mismatches") {
            val a = listOf<TraceEntry>(TraceEntry.StateEntered(0L, "t", "X"))
            val b = listOf<TraceEntry>(TraceEntry.StateEntered(0L, "t", "Y"))
            val r = TraceDiff.compare(a, b)
            val text = r.toHumanReadable()
            text shouldContain "expected:"
            text shouldContain "actual:"
        }

        test("toHumanReadable says 'match' on equal traces") {
            val a = listOf<TraceEntry>(TraceEntry.StateEntered(0L, "t", "A"))
            val r = TraceDiff.compare(a, a)
            r.toHumanReadable() shouldContain "match"
        }
    })
