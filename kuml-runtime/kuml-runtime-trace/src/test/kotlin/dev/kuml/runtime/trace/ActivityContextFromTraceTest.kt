package dev.kuml.runtime.trace

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ActivityContextFromTraceTest :
    FunSpec({

        test("extracts DecisionTaken entries in seqNo order") {
            val entries =
                listOf(
                    TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "init", clock = 0L),
                    TraceEntry.DecisionTaken(seqNo = 3L, timestamp = "", nodeId = "d2", chosenEdgeId = "e5", guard = "valid", clock = 2L),
                    TraceEntry.DecisionTaken(seqNo = 1L, timestamp = "", nodeId = "d1", chosenEdgeId = "e2", guard = null, clock = 0L),
                    TraceEntry.ActivityTerminated(seqNo = 5L, timestamp = "", clock = 3L),
                )

            val report = ActivityContextFromTrace.extract(entries)

            report.decisions.size shouldBe 2
            // seqNo-ordered: d1 (seqNo=1) before d2 (seqNo=3)
            report.decisions[0].nodeId shouldBe "d1"
            report.decisions[0].chosenEdgeId shouldBe "e2"
            report.decisions[0].guard shouldBe null
            report.decisions[1].nodeId shouldBe "d2"
            report.decisions[1].chosenEdgeId shouldBe "e5"
            report.decisions[1].guard shouldBe "valid"
        }

        test("terminated=true when ActivityTerminated present") {
            val entries =
                listOf(
                    TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "init", clock = 0L),
                    TraceEntry.ActivityTerminated(seqNo = 2L, timestamp = "", clock = 1L),
                )

            val report = ActivityContextFromTrace.extract(entries)

            report.terminated shouldBe true
            report.finalClock shouldBe 1L
        }

        test("terminated=false when no ActivityTerminated present") {
            val entries =
                listOf(
                    TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "init", clock = 0L),
                    TraceEntry.TokenConsumed(seqNo = 1L, timestamp = "", nodeId = "init", clock = 0L),
                )

            val report = ActivityContextFromTrace.extract(entries)

            report.terminated shouldBe false
            report.finalClock shouldBe null
        }

        test("toHumanReadable lists decisions and termination status") {
            val entries =
                listOf(
                    TraceEntry.DecisionTaken(
                        seqNo = 0L,
                        timestamp = "",
                        nodeId = "decide",
                        chosenEdgeId = "edgeA",
                        guard = "valid",
                        clock = 0L,
                    ),
                    TraceEntry.ActivityTerminated(seqNo = 2L, timestamp = "", clock = 2L),
                )

            val report = ActivityContextFromTrace.extract(entries)
            val text = report.toHumanReadable()

            text shouldContain "decide"
            text shouldContain "edgeA"
            text shouldContain "valid"
            text shouldContain "Final"
        }

        test("extract from TraceFile delegates to extract(entries)") {
            val entries =
                listOf(
                    TraceEntry.DecisionTaken(seqNo = 0L, timestamp = "", nodeId = "d1", chosenEdgeId = "e1", guard = null, clock = 0L),
                )
            val traceFile = TraceFile(modelId = "M", entries = entries)

            val report = ActivityContextFromTrace.extract(traceFile)

            report.decisions.size shouldBe 1
            report.terminated shouldBe false
        }
    })
