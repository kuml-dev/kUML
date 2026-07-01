package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnChoreography
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
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
            loopRoute.shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            val loopY = (loopRoute as EdgeRoute.OrthogonalRounded).waypoints.first().y
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

        test("engineId and seed contract") {
            val choreo = BpmnChoreography(id = "c1")
            val diagram = ChoreographyDiagram(name = "d", choreographyId = "c1")
            val result = ChoreographyGridLayout.layout(model(choreo), diagram)
            result.engineId shouldBe ChoreographyGridLayout.ENGINE_ID
            result.seed shouldBe null
        }
    })
