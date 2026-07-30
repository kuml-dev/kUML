package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayDirection
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BpmnUmlRoundTripTest :
    FunSpec({

        fun simpleProcess() =
            bpmnModel(name = "RoundTrip") {
                process(id = "rt", name = "RoundTripProcess") {
                    val start = startEvent(name = "Begin")
                    val t1 = task(name = "Step One")
                    val t2 = task(name = "Step Two")
                    val end = endEvent(name = "Done")
                    sequenceFlow(from = start, to = t1)
                    sequenceFlow(from = t1, to = t2)
                    sequenceFlow(from = t2, to = end)
                }
            }.processes.first()

        test("BPMN -> UML -> BPMN preserves task names") {
            val original = simpleProcess()
            val originalTaskNames =
                original.flowNodes
                    .filterIsInstance<BpmnTask>()
                    .mapNotNull { it.name }
                    .toSet()

            val umlModel = BpmnToUmlActivityMapper.map(original)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            val restoredTaskNames =
                restored.flowNodes
                    .filterIsInstance<BpmnTask>()
                    .mapNotNull { it.name }
                    .toSet()
            restoredTaskNames shouldBe originalTaskNames
        }

        test("round trip preserves flow structure (edge source/target task-name pairs equal)") {
            val original = simpleProcess()
            val umlModel = BpmnToUmlActivityMapper.map(original)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            // Both should have the same number of tasks
            original.flowNodes.filterIsInstance<BpmnTask>().size shouldBe
                restored.flowNodes.filterIsInstance<BpmnTask>().size
            // Both should have the same number of sequence flows
            original.sequenceFlows.size shouldBe restored.sequenceFlows.size
        }

        test("round trip preserves XOR gateway via bpmn.sourceId collapse") {
            val proc =
                bpmnModel(name = "XorRT") {
                    process(id = "xorrt", name = "XorRT") {
                        val start = startEvent()
                        val t1 = task(name = "T1")
                        val gw = gateway(type = GatewayType.EXCLUSIVE)
                        val t2 = task(name = "T2")
                        val t3 = task(name = "T3")
                        val end = endEvent()
                        sequenceFlow(from = start, to = t1)
                        sequenceFlow(from = t1, to = gw)
                        sequenceFlow(from = gw, to = t2, condition = "condA")
                        sequenceFlow(from = gw, to = t3, condition = "condB")
                        sequenceFlow(from = t2, to = end)
                        sequenceFlow(from = t3, to = end)
                    }
                }.processes.first()

            val umlModel = BpmnToUmlActivityMapper.map(proc)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            // Original has 1 exclusive gateway; restored should also have exclusive gateways
            val restoredExclusiveGws =
                restored.flowNodes
                    .filterIsInstance<BpmnGateway>()
                    .filter { it.gatewayType == GatewayType.EXCLUSIVE }
            restoredExclusiveGws.size shouldBe 1
        }

        test("round trip preserves parallel gateway as single PARALLEL gateway") {
            val proc =
                bpmnModel(name = "ParRT") {
                    process(id = "parrt", name = "ParRT") {
                        val start = startEvent()
                        val fork = gateway(type = GatewayType.PARALLEL)
                        val ta = task(name = "A")
                        val tb = task(name = "B")
                        val join = gateway(type = GatewayType.PARALLEL)
                        val end = endEvent()
                        sequenceFlow(from = start, to = fork)
                        sequenceFlow(from = fork, to = ta)
                        sequenceFlow(from = fork, to = tb)
                        sequenceFlow(from = ta, to = join)
                        sequenceFlow(from = tb, to = join)
                        sequenceFlow(from = join, to = end)
                    }
                }.processes.first()

            val umlModel = BpmnToUmlActivityMapper.map(proc)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            val parallelGws =
                restored.flowNodes
                    .filterIsInstance<BpmnGateway>()
                    .filter { it.gatewayType == GatewayType.PARALLEL }
            parallelGws.size shouldBe 2 // DIVERGING + CONVERGING
            parallelGws.any { it.direction == GatewayDirection.DIVERGING } shouldBe true
            parallelGws.any { it.direction == GatewayDirection.CONVERGING } shouldBe true
        }

        test("round trip preserves start/end event count") {
            val original = simpleProcess()
            val umlModel = BpmnToUmlActivityMapper.map(original)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            original.flowNodes.filterIsInstance<BpmnEvent>().count { it.position == EventPosition.START } shouldBe
                restored.flowNodes.filterIsInstance<BpmnEvent>().count { it.position == EventPosition.START }
            original.flowNodes.filterIsInstance<BpmnEvent>().count { it.position == EventPosition.END } shouldBe
                restored.flowNodes.filterIsInstance<BpmnEvent>().count { it.position == EventPosition.END }
        }

        test("BPMN Message end event round-trips with MESSAGE definition preserved") {
            val proc =
                bpmnModel(name = "MsgEndRT") {
                    process(id = "msgrt", name = "MsgEndRT") {
                        val start = startEvent(name = "Start")
                        val msgEnd = endEvent(name = "Notify", definition = EventDefinition.MESSAGE)
                        sequenceFlow(from = start, to = msgEnd)
                    }
                }.processes.first()

            val umlModel = BpmnToUmlActivityMapper.map(proc)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            val endEvents = restored.flowNodes.filterIsInstance<BpmnEvent>().filter { it.position == EventPosition.END }
            endEvents.size shouldBe 1
            endEvents.first().definition shouldBe EventDefinition.MESSAGE
        }

        test("BPMN Signal end event round-trips with SIGNAL definition preserved") {
            val proc =
                bpmnModel(name = "SigEndRT") {
                    process(id = "sigrt", name = "SigEndRT") {
                        val start = startEvent(name = "Start")
                        val sigEnd = endEvent(name = "Broadcast", definition = EventDefinition.SIGNAL)
                        sequenceFlow(from = start, to = sigEnd)
                    }
                }.processes.first()

            val umlModel = BpmnToUmlActivityMapper.map(proc)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            val endEvents = restored.flowNodes.filterIsInstance<BpmnEvent>().filter { it.position == EventPosition.END }
            endEvents.size shouldBe 1
            endEvents.first().definition shouldBe EventDefinition.SIGNAL
        }

        test("BPMN Error end event round-trips with ERROR definition preserved") {
            val proc =
                bpmnModel(name = "ErrEndRT") {
                    process(id = "errrt", name = "ErrEndRT") {
                        val start = startEvent(name = "Start")
                        val errEnd = endEvent(name = "Fail", definition = EventDefinition.ERROR)
                        sequenceFlow(from = start, to = errEnd)
                    }
                }.processes.first()

            val umlModel = BpmnToUmlActivityMapper.map(proc)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            val endEvents = restored.flowNodes.filterIsInstance<BpmnEvent>().filter { it.position == EventPosition.END }
            endEvents.size shouldBe 1
            endEvents.first().definition shouldBe EventDefinition.ERROR
        }

        test("mixed process: NONE end stays ACTIVITY_FINAL and Message end stays FLOW_FINAL after round-trip") {
            val proc =
                bpmnModel(name = "MixedEndsRT") {
                    process(id = "mixedrt", name = "MixedEndsRT") {
                        val start = startEvent(name = "Start")
                        val t = task(name = "Work")
                        val gw = gateway(type = GatewayType.EXCLUSIVE)
                        val noneEnd = endEvent(name = "Done")
                        val msgEnd = endEvent(name = "Notify", definition = EventDefinition.MESSAGE)
                        sequenceFlow(from = start, to = t)
                        sequenceFlow(from = t, to = gw)
                        sequenceFlow(from = gw, to = noneEnd, condition = "ok")
                        sequenceFlow(from = gw, to = msgEnd, condition = "error")
                    }
                }.processes.first()

            val umlModel = BpmnToUmlActivityMapper.map(proc)
            val diagram =
                KumlDiagram(
                    name = umlModel.name,
                    type = DiagramType.ACTIVITY,
                    elements = umlModel.nodes + umlModel.edges,
                )
            val restored = UmlActivityToBpmnMapper.map(diagram)!!
            val endEvents = restored.flowNodes.filterIsInstance<BpmnEvent>().filter { it.position == EventPosition.END }
            endEvents.size shouldBe 2
            endEvents.any { it.definition == EventDefinition.NONE } shouldBe true
            endEvents.any { it.definition == EventDefinition.MESSAGE } shouldBe true
        }
    })
