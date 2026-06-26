package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayDirection
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UmlActivityToBpmnTransformerTest :
    FunSpec({

        val transformer = UmlActivityToBpmnTransformer()
        val ctx = TransformContext()

        fun activityDiagram(vararg elements: dev.kuml.core.model.KumlElement) =
            KumlDiagram(
                name = "Test Activity",
                type = DiagramType.ACTIVITY,
                elements = elements.toList(),
            )

        val nodeStart = UmlActivityNode(id = "n_start", name = "", kind = UmlActivityNodeKind.INITIAL)
        val nodeTask = UmlActivityNode(id = "n_task", name = "Do Something", kind = UmlActivityNodeKind.ACTION)
        val nodeEnd = UmlActivityNode(id = "n_end", name = "", kind = UmlActivityNodeKind.ACTIVITY_FINAL)
        val edgeStartTask = UmlActivityEdge(id = "e1", sourceId = "n_start", targetId = "n_task")
        val edgeTaskEnd = UmlActivityEdge(id = "e2", sourceId = "n_task", targetId = "n_end", guard = "always")

        test("UmlActivity ACTION maps to BpmnTask name preserved") {
            val diagram = activityDiagram(nodeStart, nodeTask, nodeEnd, edgeStartTask, edgeTaskEnd)
            val process = UmlActivityToBpmnMapper.map(diagram)
            process shouldNotBe null
            val tasks = process!!.flowNodes.filterIsInstance<BpmnTask>()
            tasks.any { it.name == "Do Something" } shouldBe true
        }

        test("INITIAL maps to start event") {
            val diagram = activityDiagram(nodeStart, nodeTask, nodeEnd, edgeStartTask, edgeTaskEnd)
            val process = UmlActivityToBpmnMapper.map(diagram)!!
            val starts = process.flowNodes.filterIsInstance<BpmnEvent>().filter { it.position == EventPosition.START }
            starts.size shouldBe 1
        }

        test("ACTIVITY_FINAL maps to end event") {
            val diagram = activityDiagram(nodeStart, nodeTask, nodeEnd, edgeStartTask, edgeTaskEnd)
            val process = UmlActivityToBpmnMapper.map(diagram)!!
            val ends = process.flowNodes.filterIsInstance<BpmnEvent>().filter { it.position == EventPosition.END }
            ends.size shouldBe 1
        }

        test("edge guard maps to sequence flow conditionExpression") {
            val diagram = activityDiagram(nodeStart, nodeTask, nodeEnd, edgeStartTask, edgeTaskEnd)
            val process = UmlActivityToBpmnMapper.map(diagram)!!
            val conditions = process.sequenceFlows.mapNotNull { it.conditionExpression }
            conditions.contains("always") shouldBe true
        }

        test("DECISION maps to EXCLUSIVE gateway DIVERGING") {
            val nodeDecision = UmlActivityNode(id = "n_dec", name = "", kind = UmlActivityNodeKind.DECISION)
            val nA = UmlActivityNode(id = "n_a", name = "A", kind = UmlActivityNodeKind.ACTION)
            val nB = UmlActivityNode(id = "n_b", name = "B", kind = UmlActivityNodeKind.ACTION)
            val diagram =
                activityDiagram(
                    nodeStart,
                    nodeDecision,
                    nA,
                    nB,
                    nodeEnd,
                    UmlActivityEdge("e0", "n_start", "n_dec"),
                    UmlActivityEdge("e1", "n_dec", "n_a"),
                    UmlActivityEdge("e2", "n_dec", "n_b"),
                    UmlActivityEdge("e3", "n_a", "n_end"),
                    UmlActivityEdge("e4", "n_b", "n_end"),
                )
            val process = UmlActivityToBpmnMapper.map(diagram)!!
            val gateways = process.flowNodes.filterIsInstance<BpmnGateway>()
            val dec = gateways.firstOrNull { it.gatewayType == GatewayType.EXCLUSIVE && it.direction == GatewayDirection.DIVERGING }
            dec shouldNotBe null
        }

        test("FORK maps to PARALLEL gateway DIVERGING, JOIN maps to PARALLEL CONVERGING") {
            val nodeFork = UmlActivityNode(id = "n_fork", name = "", kind = UmlActivityNodeKind.FORK)
            val nodeJoin = UmlActivityNode(id = "n_join", name = "", kind = UmlActivityNodeKind.JOIN)
            val nA = UmlActivityNode(id = "n_a", name = "A", kind = UmlActivityNodeKind.ACTION)
            val nB = UmlActivityNode(id = "n_b", name = "B", kind = UmlActivityNodeKind.ACTION)
            val diagram =
                activityDiagram(
                    nodeStart,
                    nodeFork,
                    nA,
                    nB,
                    nodeJoin,
                    nodeEnd,
                    UmlActivityEdge("e0", "n_start", "n_fork"),
                    UmlActivityEdge("e1", "n_fork", "n_a"),
                    UmlActivityEdge("e2", "n_fork", "n_b"),
                    UmlActivityEdge("e3", "n_a", "n_join"),
                    UmlActivityEdge("e4", "n_b", "n_join"),
                    UmlActivityEdge("e5", "n_join", "n_end"),
                )
            val process = UmlActivityToBpmnMapper.map(diagram)!!
            val gateways = process.flowNodes.filterIsInstance<BpmnGateway>()
            gateways.any { it.gatewayType == GatewayType.PARALLEL && it.direction == GatewayDirection.DIVERGING } shouldBe true
            gateways.any { it.gatewayType == GatewayType.PARALLEL && it.direction == GatewayDirection.CONVERGING } shouldBe true
        }

        test("non-ACTIVITY diagram type returns TransformResult.Failure") {
            val diagram = KumlDiagram(name = "Class Diagram", type = DiagramType.CLASS)
            val result = transformer.transform(diagram, ctx)
            (result is TransformResult.Failure) shouldBe true
        }

        test("incoming and outgoing populated on flow nodes") {
            val diagram = activityDiagram(nodeStart, nodeTask, nodeEnd, edgeStartTask, edgeTaskEnd)
            val process = UmlActivityToBpmnMapper.map(diagram)!!
            val task = process.flowNodes.filterIsInstance<BpmnTask>().first { it.name == "Do Something" }
            task.incoming.isNotEmpty() shouldBe true
            task.outgoing.isNotEmpty() shouldBe true
        }
    })
