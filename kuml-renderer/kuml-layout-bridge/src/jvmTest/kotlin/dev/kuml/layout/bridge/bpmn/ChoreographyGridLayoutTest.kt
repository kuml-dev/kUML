package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnChoreography
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographyMessageFlow
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SizeProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit-Tests für [ChoreographyGridLayout] — das deterministische Custom-Grid-Layout
 * für BPMN-Choreography-Diagramme (kein ELK).
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer (custom grid layout)
 */
class ChoreographyGridLayoutTest :
    FunSpec({

        fun model(choreo: BpmnChoreography): BpmnModel = BpmnModel(name = "test-model", choreographies = listOf(choreo))

        test("linear start->task->end: strictly increasing X ranks") {
            val start = ChoreographyEvent(id = "start", position = EventPosition.START)
            val task =
                ChoreographyTask(
                    id = "t1",
                    name = "Order",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                )
            val end = ChoreographyEvent(id = "end", position = EventPosition.END)
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(task),
                    events = listOf(start, end),
                    sequenceFlows =
                        listOf(
                            ChoreographySequenceFlow(id = "sf1", sourceRef = "start", targetRef = "t1"),
                            ChoreographySequenceFlow(id = "sf2", sourceRef = "t1", targetRef = "end"),
                        ),
                )
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)

            val startX =
                result.nodes
                    .getValue(NodeId("start"))
                    .bounds.origin.x
            val taskX =
                result.nodes
                    .getValue(NodeId("t1"))
                    .bounds.origin.x
            val endX =
                result.nodes
                    .getValue(NodeId("end"))
                    .bounds.origin.x
            (taskX > startX) shouldBe true
            (endX > taskX) shouldBe true
        }

        test("parallel branches share the same rank (same X), different lanes") {
            val start = ChoreographyEvent(id = "start", position = EventPosition.START)
            val gw = ChoreographyGateway(id = "gw1", type = GatewayType.PARALLEL)
            val taskA =
                ChoreographyTask(id = "ta", initiatingParticipant = "A", participants = listOf("A", "B"))
            val taskB =
                ChoreographyTask(id = "tb", initiatingParticipant = "C", participants = listOf("C", "D"))
            val end = ChoreographyEvent(id = "end", position = EventPosition.END)
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(taskA, taskB),
                    gateways = listOf(gw),
                    events = listOf(start, end),
                    sequenceFlows =
                        listOf(
                            ChoreographySequenceFlow(id = "sf1", sourceRef = "start", targetRef = "gw1"),
                            ChoreographySequenceFlow(id = "sf2", sourceRef = "gw1", targetRef = "ta"),
                            ChoreographySequenceFlow(id = "sf3", sourceRef = "gw1", targetRef = "tb"),
                            ChoreographySequenceFlow(id = "sf4", sourceRef = "ta", targetRef = "end"),
                            ChoreographySequenceFlow(id = "sf5", sourceRef = "tb", targetRef = "end"),
                        ),
                )
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)

            val xA =
                result.nodes
                    .getValue(NodeId("ta"))
                    .bounds.origin.x
            val xB =
                result.nodes
                    .getValue(NodeId("tb"))
                    .bounds.origin.x
            xA shouldBe xB
            val yA =
                result.nodes
                    .getValue(NodeId("ta"))
                    .bounds.origin.y
            val yB =
                result.nodes
                    .getValue(NodeId("tb"))
                    .bounds.origin.y
            (yA != yB) shouldBe true
        }

        test("lane assignment: participant first-appearance order, task spans lanes") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val t2 = ChoreographyTask(id = "t2", initiatingParticipant = "B", participants = listOf("B", "C"))
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(t1, t2),
                    sequenceFlows = listOf(ChoreographySequenceFlow(id = "sf1", sourceRef = "t1", targetRef = "t2")),
                )
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)

            // A=lane0, B=lane1, C=lane2. t1 spans [0,1], t2 spans [1,2].
            val t1Bounds = result.nodes.getValue(NodeId("t1")).bounds
            val t2Bounds = result.nodes.getValue(NodeId("t2")).bounds
            t1Bounds.origin.y shouldBe ChoreographyGridLayout.MARGIN
            (t2Bounds.origin.y > t1Bounds.origin.y) shouldBe true
        }

        test("task straddle height is at least TASK_MIN_H") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val choreo = BpmnChoreography(id = "c1", tasks = listOf(t1))
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            val h =
                result.nodes
                    .getValue(NodeId("t1"))
                    .bounds.size.height
            (h >= ChoreographyGridLayout.TASK_MIN_H) shouldBe true
        }

        test("same-lane adjacent columns produce Direct edges; cross-lane produce OrthogonalRounded") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val t2 = ChoreographyTask(id = "t2", initiatingParticipant = "A", participants = listOf("A", "B"))
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(t1, t2),
                    sequenceFlows = listOf(ChoreographySequenceFlow(id = "sf1", sourceRef = "t1", targetRef = "t2")),
                )
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            val route = result.edges.getValue(EdgeId("sf1"))
            route.shouldBeInstanceOf<EdgeRoute.Direct>()
        }

        test("branch edge skipping over an intervening cross-lane task does not cross its bounds") {
            // Reproduziert Beispiel „37 BPMN Choreography": ein EXCLUSIVE-Gateway auf der
            // Spine-Spur verzweigt zu einem Task in einer OBEREN Spur (lieferung) und
            // überspringt dabei einen Task (nachbestellung), der die Spine-Spur mit
            // abdeckt. Die frühere midX-Route lief horizontal auf Gateway-Y quer durch
            // die nachbestellung-Box. Die obstacle-aware Route muss das vermeiden.
            val start = ChoreographyEvent(id = "start", position = EventPosition.START)
            val bestellung =
                ChoreographyTask(id = "bestellung", initiatingParticipant = "Kunde", participants = listOf("Kunde", "Händler"))
            val gw = ChoreographyGateway(id = "gw", type = GatewayType.EXCLUSIVE)
            val nachbestellung =
                ChoreographyTask(id = "nachbestellung", initiatingParticipant = "Händler", participants = listOf("Händler", "Lieferant"))
            val lieferung =
                ChoreographyTask(id = "lieferung", initiatingParticipant = "Händler", participants = listOf("Händler", "Kunde"))
            val end = ChoreographyEvent(id = "end", position = EventPosition.END)
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(bestellung, nachbestellung, lieferung),
                    gateways = listOf(gw),
                    events = listOf(start, end),
                    sequenceFlows =
                        listOf(
                            ChoreographySequenceFlow(id = "sf1", sourceRef = "start", targetRef = "bestellung"),
                            ChoreographySequenceFlow(id = "sf2", sourceRef = "bestellung", targetRef = "gw"),
                            ChoreographySequenceFlow(id = "sf3", sourceRef = "gw", targetRef = "lieferung"),
                            ChoreographySequenceFlow(id = "sf4", sourceRef = "gw", targetRef = "nachbestellung"),
                            ChoreographySequenceFlow(id = "sf5", sourceRef = "nachbestellung", targetRef = "lieferung"),
                            ChoreographySequenceFlow(id = "sf6", sourceRef = "lieferung", targetRef = "end"),
                        ),
                )
            val result =
                ChoreographyGridLayout.layout(model(choreo), ChoreographyDiagram(name = "d", choreographyId = "c1"))

            // Die Route gw → lieferung (sf3) darf die nachbestellung-Box nicht durchschneiden.
            val skipRoute = result.edges.getValue(EdgeId("sf3")).shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            val obstacle = result.nodes.getValue(NodeId("nachbestellung")).bounds
            val pts = listOf(skipRoute.source) + skipRoute.waypoints + listOf(skipRoute.target)
            val eps = 0.5f
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val left = obstacle.origin.x
                val right = obstacle.origin.x + obstacle.size.width
                val top = obstacle.origin.y
                val bottom = obstacle.origin.y + obstacle.size.height
                val hits =
                    if (a.y == b.y) {
                        a.y > top + eps &&
                            a.y < bottom - eps &&
                            maxOf(minOf(a.x, b.x), left) < minOf(maxOf(a.x, b.x), right) - eps
                    } else {
                        a.x > left + eps &&
                            a.x < right - eps &&
                            maxOf(minOf(a.y, b.y), top) < minOf(maxOf(a.y, b.y), bottom) - eps
                    }
                hits shouldBe false
            }
        }

        test("loop back-edge routed below all lanes as OrthogonalRounded") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val t2 = ChoreographyTask(id = "t2", initiatingParticipant = "A", participants = listOf("A", "B"))
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(t1, t2),
                    sequenceFlows =
                        listOf(
                            ChoreographySequenceFlow(id = "sf1", sourceRef = "t1", targetRef = "t2"),
                            ChoreographySequenceFlow(id = "loop", sourceRef = "t2", targetRef = "t1"),
                        ),
                )
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            val loopRoute = result.edges.getValue(EdgeId("loop"))
            val loopY =
                loopRoute
                    .shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
                    .waypoints
                    .first()
                    .y
            (loopY < result.canvas.height) shouldBe true
            (
                loopY >
                    result.nodes
                        .getValue(NodeId("t1"))
                        .bounds.origin.y
            ) shouldBe true
        }

        test("canvas sizing matches last column + last lane + margins") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val choreo = BpmnChoreography(id = "c1", tasks = listOf(t1))
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            result.canvas.width shouldBe (ChoreographyGridLayout.MARGIN + ChoreographyGridLayout.TASK_W + ChoreographyGridLayout.MARGIN)
        }

        test("determinism: two layout calls produce structurally equal results") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(t1),
                    events = listOf(ChoreographyEvent(id = "start", position = EventPosition.START)),
                    sequenceFlows = listOf(ChoreographySequenceFlow(id = "sf1", sourceRef = "start", targetRef = "t1")),
                )
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val r1 = ChoreographyGridLayout.layout(model(choreo), diagram)
            val r2 = ChoreographyGridLayout.layout(model(choreo), diagram)
            r1 shouldBe r2
        }

        test("unknown choreographyId yields empty LayoutResult, no throw") {
            val choreo = BpmnChoreography(id = "c1")
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "does-not-exist")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            result.nodes.size shouldBe 0
            result.edges.size shouldBe 0
        }

        test("element filter: only listed elements + flows with both endpoints survive") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val t2 = ChoreographyTask(id = "t2", initiatingParticipant = "A", participants = listOf("A", "B"))
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(t1, t2),
                    sequenceFlows = listOf(ChoreographySequenceFlow(id = "sf1", sourceRef = "t1", targetRef = "t2")),
                )
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1", elementIds = listOf("t1"))
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            result.nodes.size shouldBe 1
            result.edges.size shouldBe 0
        }

        test("SizeProvider override flows into node bounds width") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val choreo = BpmnChoreography(id = "c1", tasks = listOf(t1))
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val sizeProvider = SizeProvider { _, _ -> Size(240f, 80f) }
            val result = ChoreographyGridLayout.layout(model(choreo), diagram, sizeProvider)
            result.nodes
                .getValue(NodeId("t1"))
                .bounds.size.width shouldBe 240f
        }

        test("adjacent tasks with message envelopes: reserved lane gap prevents envelope overlap") {
            // t1 spans lanes [0,1] with a RETURN envelope (overhangs 15px below into lane 2);
            // t2 spans lanes [2,3] with an INITIATING envelope (overhangs 15px above into lane 1).
            // Both sit in the SAME column (parallel) so only the vertical gap separates them.
            val t1 =
                ChoreographyTask(
                    id = "t1",
                    initiatingParticipant = "A",
                    participants = listOf("A", "B"),
                    messageFlows =
                        listOf(
                            ChoreographyMessageFlow(id = "m1", participantRef = "A", isInitiating = false),
                        ),
                )
            val t2 =
                ChoreographyTask(
                    id = "t2",
                    initiatingParticipant = "C",
                    participants = listOf("C", "D"),
                    messageFlows =
                        listOf(
                            ChoreographyMessageFlow(id = "m2", participantRef = "C", isInitiating = true),
                        ),
                )
            // force same column via a fork
            val gw = ChoreographyGateway(id = "gw", type = GatewayType.PARALLEL)
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(t1, t2),
                    gateways = listOf(gw),
                    sequenceFlows =
                        listOf(
                            ChoreographySequenceFlow(id = "f1", sourceRef = "gw", targetRef = "t1"),
                            ChoreographySequenceFlow(id = "f2", sourceRef = "gw", targetRef = "t2"),
                        ),
                )
            val result =
                ChoreographyGridLayout.layout(model(choreo), ChoreographyDiagram(name = "d", choreographyId = "c1"))
            val b1 = result.nodes.getValue(NodeId("t1")).bounds
            val b2 = result.nodes.getValue(NodeId("t2")).bounds
            // t1 bottom envelope reaches b1.bottom + 15; t2 top envelope reaches b2.top - 15.
            val t1EnvBottom = b1.origin.y + b1.size.height + ChoreographyGridLayout.ENVELOPE_RESERVE
            val t2EnvTop = b2.origin.y - ChoreographyGridLayout.ENVELOPE_RESERVE
            (t2EnvTop >= t1EnvBottom) shouldBe true // no vertical overlap of glyph footprints
        }

        test("no message flows: lanes remain contiguous (no reserved gap)") {
            val t1 = ChoreographyTask(id = "t1", initiatingParticipant = "A", participants = listOf("A", "B"))
            val t2 = ChoreographyTask(id = "t2", initiatingParticipant = "B", participants = listOf("B", "C"))
            val choreo =
                BpmnChoreography(
                    id = "c1",
                    tasks = listOf(t1, t2),
                    sequenceFlows = listOf(ChoreographySequenceFlow(id = "sf1", sourceRef = "t1", targetRef = "t2")),
                )
            val result =
                ChoreographyGridLayout.layout(model(choreo), ChoreographyDiagram(name = "d", choreographyId = "c1"))
            // lane1 top == MARGIN + LANE_HEIGHT exactly (no gap)
            result.nodes
                .getValue(NodeId("t2"))
                .bounds.origin.y shouldBe
                (ChoreographyGridLayout.MARGIN + ChoreographyGridLayout.LANE_HEIGHT)
        }

        test("engineId and seed contract") {
            val choreo = BpmnChoreography(id = "c1")
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            result.engineId shouldBe ChoreographyGridLayout.ENGINE_ID
            result.seed shouldBe null
        }
    })
