package dev.kuml.runtime.trace

import dev.kuml.runtime.Event
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TraceReplayerTest :
    FunSpec({

        test("happy-path roundtrip — isMatch true for same model and events") {
            val sm = oneEventSm()
            val original = simulateToTraceFile(sm, listOf(Event.of("go")))

            val report = TraceReplayer().replay(sm, original)

            report.isMatch shouldBe true
            report.originalSize shouldBe original.entries.size
            report.actualSize shouldBe original.entries.size
        }

        test("mismatch when replaying fewer events than the original trace has") {
            val sm = twoStateSmWithFinal()
            // Record full trace with two events (goes all the way to final)
            val fullTrace = simulateToTraceFile(sm, listOf(Event.of("go"), Event.of("done")))

            // Now record partial trace (only "go") — different size than full trace
            val partialTrace = simulateToTraceFile(sm, listOf(Event.of("go")))

            // Replay using only the events from partialTrace but compare against fullTrace entries
            // We splice: replay the partial-event model against the full-trace entries
            // Simplest approach: build a TraceFile with full trace entries but only partial events embedded
            val mixedTraceFile =
                TraceFile(
                    modelId = sm.id,
                    entries = fullTrace.entries,
                )
            // Extract events from partial trace but replay against full entries
            // Actually: replay the sm against the full trace (match expected), then compare
            // A simpler mismatch: same model, but we corrupt the entries count
            val corruptedTrace =
                TraceFile(
                    modelId = sm.id,
                    entries = fullTrace.entries.take(1), // only 1 entry — way fewer than actual
                )
            val report = TraceReplayer().replay(sm, corruptedTrace)

            // Replay with two events will produce a much longer trace than 1 entry
            report.isMatch shouldBe false
        }

        test("replay with two events produces Terminated entry in actual trace") {
            val sm = twoStateSmWithFinal()
            val original = simulateToTraceFile(sm, listOf(Event.of("go"), Event.of("done")))

            val report = TraceReplayer().replay(sm, original)

            report.isMatch shouldBe true
            report.actualTrace.any { it is TraceEntry.Terminated } shouldBe true
        }

        test("throws UnsupportedTraceFlavourException for Activity-flavoured trace") {
            val sm = oneEventSm()
            val actTrace = activityTraceFile()

            shouldThrow<UnsupportedTraceFlavourException> {
                TraceReplayer().replay(sm, actTrace)
            }
        }

        test("replay is deterministic — two replays produce identical traces") {
            val sm = twoStateSmWithFinal()
            val original = simulateToTraceFile(sm, listOf(Event.of("go"), Event.of("done")))

            val report1 = TraceReplayer().replay(sm, original)
            val report2 = TraceReplayer().replay(sm, original)

            report1.isMatch shouldBe report2.isMatch
            report1.actualSize shouldBe report2.actualSize
            // Timestamps differ only by epoch clock increment — entries structurally identical
            report1.actualTrace.size shouldBe report2.actualTrace.size
            for (i in report1.actualTrace.indices) {
                report1.actualTrace[i]::class shouldBe report2.actualTrace[i]::class
            }
        }

        test("events list in report matches extracted events from original") {
            val sm = twoStateSmWithFinal()
            val original = simulateToTraceFile(sm, listOf(Event.of("go"), Event.of("done")))

            val report = TraceReplayer().replay(sm, original)

            report.events.map { it.name } shouldBe listOf("go", "done")
        }

        test("toHumanReadable returns match summary when isMatch is true") {
            val sm = oneEventSm()
            val original = simulateToTraceFile(sm, listOf(Event.of("go")))
            val report = TraceReplayer().replay(sm, original)

            val text = report.toHumanReadable()
            text.contains("match", ignoreCase = true) shouldBe true
        }
    })
