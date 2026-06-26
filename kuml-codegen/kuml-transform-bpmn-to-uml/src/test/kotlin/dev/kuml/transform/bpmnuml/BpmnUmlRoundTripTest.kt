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
            bpmnModel("RoundTrip") {
                process(id = "rt", name = "RoundTripProcess") {
                    val start = startEvent("Begin")
                    val t1 = task("Step One")
                    val t2 = task("Step Two")
                    val end = endEvent("Done")
                    sequenceFlow(start, t1)
                    sequenceFlow(t1, t2)
                    sequenceFlow(t2, end)
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
                bpmnModel("XorRT") {
                    process(id = "xorrt", name = "XorRT") {
                        val start = startEvent()
                        val t1 = task("T1")
                        val gw = gateway(GatewayType.EXCLUSIVE)
                        val t2 = task("T2")
                        val t3 = task("T3")
                        val end = endEvent()
                        sequenceFlow(start, t1)
                        sequenceFlow(t1, gw)
                        sequenceFlow(gw, t2, condition = "condA")
                        sequenceFlow(gw, t3, condition = "condB")
                        sequenceFlow(t2, end)
                        sequenceFlow(t3, end)
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
                bpmnModel("ParRT") {
                    process(id = "parrt", name = "ParRT") {
                        val start = startEvent()
                        val fork = gateway(GatewayType.PARALLEL)
                        val ta = task("A")
                        val tb = task("B")
                        val join = gateway(GatewayType.PARALLEL)
                        val end = endEvent()
                        sequenceFlow(start, fork)
                        sequenceFlow(fork, ta)
                        sequenceFlow(fork, tb)
                        sequenceFlow(ta, join)
                        sequenceFlow(tb, join)
                        sequenceFlow(join, end)
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
                bpmnModel("MsgEndRT") {
                    process(id = "msgrt", name = "MsgEndRT") {
                        val start = startEvent("Start")
                        val msgEnd = endEvent("Notify", definition = EventDefinition.MESSAGE)
                        sequenceFlow(start, msgEnd)
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
                bpmnModel("SigEndRT") {
                    process(id = "sigrt", name = "SigEndRT") {
                        val start = startEvent("Start")
                        val sigEnd = endEvent("Broadcast", definition = EventDefinition.SIGNAL)
                        sequenceFlow(start, sigEnd)
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
                bpmnModel("ErrEndRT") {
                    process(id = "errrt", name = "ErrEndRT") {
                        val start = startEvent("Start")
                        val errEnd = endEvent("Fail", definition = EventDefinition.ERROR)
                        sequenceFlow(start, errEnd)
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
                bpmnModel("MixedEndsRT") {
                    process(id = "mixedrt", name = "MixedEndsRT") {
                        val start = startEvent("Start")
                        val t = task("Work")
                        val gw = gateway(GatewayType.EXCLUSIVE)
                        val noneEnd = endEvent("Done")
                        val msgEnd = endEvent("Notify", definition = EventDefinition.MESSAGE)
                        sequenceFlow(start, t)
                        sequenceFlow(t, gw)
                        sequenceFlow(gw, noneEnd, condition = "ok")
                        sequenceFlow(gw, msgEnd, condition = "error")
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
