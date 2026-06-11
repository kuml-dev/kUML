package dev.kuml.runtime.trace

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TraceFlavourDetectorTest :
    FunSpec({

        test("pure STM entries → STM flavour") {
            val entries =
                listOf(
                    TraceEntry.StateEntered(seqNo = 0L, timestamp = "", vertexId = "A"),
                    TraceEntry.TransitionFired(seqNo = 1L, timestamp = "", transitionId = "t1", fromVertexId = "A", toVertexId = "B"),
                    TraceEntry.Terminated(seqNo = 2L, timestamp = "", finalVertexId = "fin"),
                )

            TraceFlavourDetector.detect(entries) shouldBe TraceFlavour.STM
        }

        test("pure Activity entries → ACTIVITY flavour") {
            val entries =
                listOf(
                    TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "init", clock = 0L),
                    TraceEntry.TokenConsumed(seqNo = 1L, timestamp = "", nodeId = "init", clock = 0L),
                    TraceEntry.ActivityTerminated(seqNo = 2L, timestamp = "", clock = 1L),
                )

            TraceFlavourDetector.detect(entries) shouldBe TraceFlavour.ACTIVITY
        }

        test("empty entries → EMPTY flavour") {
            TraceFlavourDetector.detect(emptyList()) shouldBe TraceFlavour.EMPTY
        }

        test("mixed STM and Activity entries → MIXED flavour") {
            val entries =
                listOf(
                    TraceEntry.StateEntered(seqNo = 0L, timestamp = "", vertexId = "A"),
                    TraceEntry.TokenPlaced(seqNo = 1L, timestamp = "", nodeId = "init", clock = 0L),
                )

            TraceFlavourDetector.detect(entries) shouldBe TraceFlavour.MIXED
        }

        test("detect from TraceFile delegates correctly") {
            val traceFile =
                TraceFile(
                    modelId = "M",
                    entries =
                        listOf(
                            TraceEntry.ActivityActionInvoked(seqNo = 0L, timestamp = "", nodeId = "n1", body = null, clock = 0L),
                        ),
                )

            TraceFlavourDetector.detect(traceFile) shouldBe TraceFlavour.ACTIVITY
        }

        test("all STM entry kinds are recognised as STM") {
            val stmEntries =
                listOf(
                    TraceEntry.EventReceived(
                        seqNo = 0L,
                        timestamp = "",
                        eventName = "e",
                        payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                    TraceEntry.StateEntered(seqNo = 1L, timestamp = "", vertexId = "A"),
                    TraceEntry.StateExited(seqNo = 2L, timestamp = "", vertexId = "A"),
                    TraceEntry.ActionInvoked(
                        seqNo = 3L,
                        timestamp = "",
                        phase = dev.kuml.runtime.ActionPhase.EFFECT,
                        action = "act()",
                        vertexId = null,
                        transitionId = "t1",
                    ),
                    TraceEntry.TransitionFired(seqNo = 4L, timestamp = "", transitionId = "t1", fromVertexId = "A", toVertexId = "B"),
                    TraceEntry.GuardEvaluated(seqNo = 5L, timestamp = "", transitionId = "t1", guard = "[x]", result = true),
                    TraceEntry.GuardWarning(seqNo = 6L, timestamp = "", transitionId = "t1", guard = "[x]", message = "w"),
                    TraceEntry.ActionError(seqNo = 7L, timestamp = "", transitionId = null, message = "err"),
                    TraceEntry.Stayed(seqNo = 8L, timestamp = "", reason = "no match"),
                    TraceEntry.Terminated(seqNo = 9L, timestamp = "", finalVertexId = "fin"),
                )

            TraceFlavourDetector.detect(stmEntries) shouldBe TraceFlavour.STM
        }

        test("all Activity entry kinds are recognised as ACTIVITY") {
            val actEntries =
                listOf(
                    TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "", nodeId = "n1", clock = 0L),
                    TraceEntry.TokenConsumed(seqNo = 1L, timestamp = "", nodeId = "n1", clock = 0L),
                    TraceEntry.DecisionTaken(seqNo = 2L, timestamp = "", nodeId = "d1", chosenEdgeId = "e1", guard = null, clock = 0L),
                    TraceEntry.ForkSplit(seqNo = 3L, timestamp = "", nodeId = "f1", targetNodeIds = listOf("a", "b"), clock = 0L),
                    TraceEntry.JoinReached(
                        seqNo = 4L,
                        timestamp = "",
                        nodeId = "j1",
                        awaitingEdgeIds = emptyList(),
                        isReady = true,
                        clock = 0L,
                    ),
                    TraceEntry.ActivityActionInvoked(seqNo = 5L, timestamp = "", nodeId = "a1", body = null, clock = 0L),
                    TraceEntry.FlowFinalConsumed(seqNo = 6L, timestamp = "", nodeId = "ff1", clock = 0L),
                    TraceEntry.ActivityTerminated(seqNo = 7L, timestamp = "", clock = 1L),
                )

            TraceFlavourDetector.detect(actEntries) shouldBe TraceFlavour.ACTIVITY
        }
    })
