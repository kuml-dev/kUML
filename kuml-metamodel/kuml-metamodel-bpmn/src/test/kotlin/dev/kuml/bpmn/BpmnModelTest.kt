package dev.kuml.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.bpmn.model.BpmnDataStore
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.DataAssociation
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayDirection
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MultiInstanceLoop
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.StandardLoop
import dev.kuml.bpmn.model.TaskType
import dev.kuml.core.model.KumlMetaValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BpmnModelTest :
    DescribeSpec({

        val json = Json { prettyPrint = false }

        // ── Construction + JSON roundtrip ─────────────────────────────────────────

        describe("BpmnTask") {
            it("round-trips with default values") {
                val task = BpmnTask(id = "t1", name = "Review Order")
                val decoded = json.decodeFromString<BpmnTask>(json.encodeToString(task))
                decoded shouldBe task
            }

            it("round-trips with SERVICE type") {
                val task = BpmnTask(id = "t2", name = "Call API", taskType = TaskType.SERVICE)
                val decoded = json.decodeFromString<BpmnTask>(json.encodeToString(task))
                decoded.taskType shouldBe TaskType.SERVICE
            }

            it("stores metadata") {
                val task =
                    BpmnTask(
                        id = "t3",
                        metadata = mapOf("priority" to KumlMetaValue.Integer(1)),
                    )
                val decoded = json.decodeFromString<BpmnTask>(json.encodeToString(task))
                decoded.metadata["priority"] shouldBe KumlMetaValue.Integer(1)
            }
        }

        describe("BpmnSubProcess") {
            it("round-trips with expanded=true") {
                val sp = BpmnSubProcess(id = "sp1", name = "Order Handling", expanded = true)
                val decoded = json.decodeFromString<BpmnSubProcess>(json.encodeToString(sp))
                decoded.expanded shouldBe true
            }

            it("round-trips with triggeredByEvent=true") {
                val sp = BpmnSubProcess(id = "sp2", triggeredByEvent = true)
                val decoded = json.decodeFromString<BpmnSubProcess>(json.encodeToString(sp))
                decoded.triggeredByEvent shouldBe true
            }

            it("round-trips with transactional=true") {
                val sp = BpmnSubProcess(id = "sp3", transactional = true)
                val decoded = json.decodeFromString<BpmnSubProcess>(json.encodeToString(sp))
                decoded.transactional shouldBe true
            }
        }

        describe("BpmnCallActivity") {
            it("round-trips with calledElement") {
                val ca = BpmnCallActivity(id = "ca1", calledElement = "OrderProcess")
                val decoded = json.decodeFromString<BpmnCallActivity>(json.encodeToString(ca))
                decoded.calledElement shouldBe "OrderProcess"
            }
        }

        describe("BpmnGateway") {
            it("round-trips EXCLUSIVE gateway") {
                val gw = BpmnGateway(id = "gw1", gatewayType = GatewayType.EXCLUSIVE)
                val decoded = json.decodeFromString<BpmnGateway>(json.encodeToString(gw))
                decoded.gatewayType shouldBe GatewayType.EXCLUSIVE
            }

            it("round-trips INCLUSIVE gateway") {
                val gw = BpmnGateway(id = "gw2", gatewayType = GatewayType.INCLUSIVE)
                json.decodeFromString<BpmnGateway>(json.encodeToString(gw)).gatewayType shouldBe GatewayType.INCLUSIVE
            }

            it("round-trips PARALLEL gateway") {
                val gw = BpmnGateway(id = "gw3", gatewayType = GatewayType.PARALLEL)
                json.decodeFromString<BpmnGateway>(json.encodeToString(gw)).gatewayType shouldBe GatewayType.PARALLEL
            }

            it("round-trips EVENT_BASED gateway") {
                val gw = BpmnGateway(id = "gw4", gatewayType = GatewayType.EVENT_BASED)
                json.decodeFromString<BpmnGateway>(json.encodeToString(gw)).gatewayType shouldBe GatewayType.EVENT_BASED
            }

            it("round-trips COMPLEX gateway with direction") {
                val gw =
                    BpmnGateway(
                        id = "gw5",
                        gatewayType = GatewayType.COMPLEX,
                        direction = GatewayDirection.DIVERGING,
                    )
                val decoded = json.decodeFromString<BpmnGateway>(json.encodeToString(gw))
                decoded.gatewayType shouldBe GatewayType.COMPLEX
                decoded.direction shouldBe GatewayDirection.DIVERGING
            }
        }

        describe("BpmnEvent") {
            it("round-trips START + MESSAGE + CATCHING") {
                val e =
                    BpmnEvent(
                        id = "e1",
                        position = EventPosition.START,
                        definition = EventDefinition.MESSAGE,
                        behaviour = EventBehaviour.CATCHING,
                    )
                val decoded = json.decodeFromString<BpmnEvent>(json.encodeToString(e))
                decoded.position shouldBe EventPosition.START
                decoded.definition shouldBe EventDefinition.MESSAGE
            }

            it("round-trips INTERMEDIATE + TIMER + CATCHING") {
                val e =
                    BpmnEvent(
                        id = "e2",
                        position = EventPosition.INTERMEDIATE,
                        definition = EventDefinition.TIMER,
                        behaviour = EventBehaviour.CATCHING,
                    )
                json.decodeFromString<BpmnEvent>(json.encodeToString(e)).definition shouldBe EventDefinition.TIMER
            }

            it("round-trips INTERMEDIATE + MESSAGE + THROWING") {
                val e =
                    BpmnEvent(
                        id = "e3",
                        position = EventPosition.INTERMEDIATE,
                        definition = EventDefinition.MESSAGE,
                        behaviour = EventBehaviour.THROWING,
                    )
                json.decodeFromString<BpmnEvent>(json.encodeToString(e)).behaviour shouldBe EventBehaviour.THROWING
            }

            it("round-trips END + TERMINATE + THROWING") {
                val e =
                    BpmnEvent(
                        id = "e4",
                        position = EventPosition.END,
                        definition = EventDefinition.TERMINATE,
                        behaviour = EventBehaviour.THROWING,
                    )
                json.decodeFromString<BpmnEvent>(json.encodeToString(e)).definition shouldBe EventDefinition.TERMINATE
            }
        }

        describe("SequenceFlow") {
            it("round-trips basic flow") {
                val sf = SequenceFlow(id = "sf1", sourceRef = "t1", targetRef = "gw1")
                val decoded = json.decodeFromString<SequenceFlow>(json.encodeToString(sf))
                decoded shouldBe sf
            }

            it("round-trips flow with condition") {
                val sf =
                    SequenceFlow(
                        id = "sf2",
                        sourceRef = "gw1",
                        targetRef = "t2",
                        conditionExpression = "\${amount > 1000}",
                        isDefault = false,
                    )
                val decoded = json.decodeFromString<SequenceFlow>(json.encodeToString(sf))
                decoded.conditionExpression shouldBe "\${amount > 1000}"
            }
        }

        describe("BpmnDataObject") {
            it("round-trips basic data object") {
                val d = BpmnDataObject(id = "do1", name = "Order")
                val decoded = json.decodeFromString<BpmnDataObject>(json.encodeToString(d))
                decoded shouldBe d
            }

            it("round-trips collection data object") {
                val d = BpmnDataObject(id = "do2", name = "Order Items", collection = true)
                val decoded = json.decodeFromString<BpmnDataObject>(json.encodeToString(d))
                decoded.collection shouldBe true
            }
        }

        describe("BpmnDataStore") {
            it("round-trips data store with unlimited=false") {
                val ds = BpmnDataStore(id = "ds1", name = "Customer DB")
                val decoded = json.decodeFromString<BpmnDataStore>(json.encodeToString(ds))
                decoded shouldBe ds
            }
        }

        // ── Event position guards ─────────────────────────────────────────────────

        describe("BpmnEvent position guards") {
            it("rejects START + THROWING") {
                shouldThrow<IllegalArgumentException> {
                    BpmnEvent(
                        id = "bad1",
                        position = EventPosition.START,
                        behaviour = EventBehaviour.THROWING,
                    )
                }
            }

            it("rejects END + CATCHING") {
                shouldThrow<IllegalArgumentException> {
                    BpmnEvent(
                        id = "bad2",
                        position = EventPosition.END,
                        behaviour = EventBehaviour.CATCHING,
                    )
                }
            }
        }

        // ── Event definition guards ───────────────────────────────────────────────

        describe("BpmnEvent definition guards") {
            it("rejects TERMINATE at START position") {
                shouldThrow<IllegalArgumentException> {
                    BpmnEvent(
                        id = "bad3",
                        position = EventPosition.START,
                        definition = EventDefinition.TERMINATE,
                        behaviour = EventBehaviour.CATCHING,
                    )
                }
            }

            it("rejects TERMINATE at INTERMEDIATE position") {
                shouldThrow<IllegalArgumentException> {
                    BpmnEvent(
                        id = "bad4",
                        position = EventPosition.INTERMEDIATE,
                        definition = EventDefinition.TERMINATE,
                        behaviour = EventBehaviour.CATCHING,
                    )
                }
            }

            it("rejects LINK at START position") {
                shouldThrow<IllegalArgumentException> {
                    BpmnEvent(
                        id = "bad5",
                        position = EventPosition.START,
                        definition = EventDefinition.LINK,
                        behaviour = EventBehaviour.CATCHING,
                    )
                }
            }

            it("rejects LINK at END position") {
                shouldThrow<IllegalArgumentException> {
                    BpmnEvent(
                        id = "bad6",
                        position = EventPosition.END,
                        definition = EventDefinition.LINK,
                        behaviour = EventBehaviour.THROWING,
                    )
                }
            }

            it("rejects CANCEL at INTERMEDIATE position") {
                shouldThrow<IllegalArgumentException> {
                    BpmnEvent(
                        id = "bad7",
                        position = EventPosition.INTERMEDIATE,
                        definition = EventDefinition.CANCEL,
                        behaviour = EventBehaviour.CATCHING,
                    )
                }
            }
        }

        // ── LoopCharacteristics ───────────────────────────────────────────────────

        describe("LoopCharacteristics on BpmnTask") {
            it("attaches StandardLoop with testBefore=true") {
                val task =
                    BpmnTask(
                        id = "lt1",
                        loopCharacteristics = StandardLoop(testBefore = true, loopCondition = "\${count < 3}"),
                    )
                val decoded = json.decodeFromString<BpmnTask>(json.encodeToString(task))
                val lc = decoded.loopCharacteristics.shouldBeInstanceOf<StandardLoop>()
                lc.testBefore shouldBe true
            }

            it("attaches StandardLoop with loopCondition") {
                val task =
                    BpmnTask(
                        id = "lt2",
                        loopCharacteristics = StandardLoop(loopCondition = "\${retry < 5}"),
                    )
                val lc = (json.decodeFromString<BpmnTask>(json.encodeToString(task)).loopCharacteristics as StandardLoop)
                lc.loopCondition shouldBe "\${retry < 5}"
            }

            it("attaches MultiInstanceLoop sequential") {
                val task =
                    BpmnTask(
                        id = "lt3",
                        loopCharacteristics = MultiInstanceLoop(sequential = true, cardinality = "5"),
                    )
                val lc = (json.decodeFromString<BpmnTask>(json.encodeToString(task)).loopCharacteristics as MultiInstanceLoop)
                lc.sequential shouldBe true
                lc.cardinality shouldBe "5"
            }

            it("attaches MultiInstanceLoop parallel") {
                val task =
                    BpmnTask(
                        id = "lt4",
                        loopCharacteristics = MultiInstanceLoop(sequential = false),
                    )
                val lc = (json.decodeFromString<BpmnTask>(json.encodeToString(task)).loopCharacteristics as MultiInstanceLoop)
                lc.sequential shouldBe false
            }
        }

        // ── BpmnProcess.elementById ───────────────────────────────────────────────

        describe("BpmnProcess.elementById") {
            val task = BpmnTask(id = "node1", name = "Task A")
            val sf = SequenceFlow(id = "sf1", sourceRef = "node1", targetRef = "node2")
            val dataObj = BpmnDataObject(id = "do1", name = "Data")
            val dataAssoc = DataAssociation(id = "da1", sourceRef = "do1", targetRef = "node1")
            val process =
                BpmnProcess(
                    id = "proc1",
                    name = "Test Process",
                    flowNodes = listOf(task),
                    sequenceFlows = listOf(sf),
                    dataObjects = listOf(dataObj),
                    dataAssociations = listOf(dataAssoc),
                )

            it("finds a flow node by ID") {
                process.elementById("node1").shouldNotBeNull().shouldBeInstanceOf<BpmnTask>()
            }

            it("finds a sequence flow by ID") {
                process.elementById("sf1").shouldNotBeNull().shouldBeInstanceOf<SequenceFlow>()
            }

            it("finds a data object by ID") {
                process.elementById("do1").shouldNotBeNull().shouldBeInstanceOf<BpmnDataObject>()
            }

            it("finds a data association by ID") {
                process.elementById("da1").shouldNotBeNull().shouldBeInstanceOf<DataAssociation>()
            }

            it("returns null for unknown ID") {
                process.elementById("nonexistent").shouldBeNull()
            }
        }

        // ── BpmnModel.elementById ─────────────────────────────────────────────────

        describe("BpmnModel.elementById") {
            val taskA = BpmnTask(id = "taskA")
            val taskB = BpmnTask(id = "taskB")
            val procA = BpmnProcess(id = "procA", flowNodes = listOf(taskA))
            val procB = BpmnProcess(id = "procB", flowNodes = listOf(taskB))
            val model = BpmnModel(name = "Multi-Process Model", processes = listOf(procA, procB))

            it("finds element in first process") {
                model.elementById("taskA").shouldNotBeNull()
            }

            it("finds element in second process") {
                model.elementById("taskB").shouldNotBeNull()
            }
        }

        // ── BpmnProcess JSON roundtrip ────────────────────────────────────────────

        describe("BpmnProcess JSON roundtrip") {
            it("round-trips a process containing BpmnTask, BpmnGateway, BpmnEvent, SequenceFlow, and DataObject") {
                val task = BpmnTask(id = "t1", name = "Review", taskType = TaskType.USER)
                val gateway =
                    BpmnGateway(
                        id = "gw1",
                        gatewayType = GatewayType.EXCLUSIVE,
                        direction = GatewayDirection.DIVERGING,
                    )
                val event =
                    BpmnEvent(
                        id = "e1",
                        position = EventPosition.START,
                        definition = EventDefinition.NONE,
                        behaviour = EventBehaviour.CATCHING,
                    )
                val sf1 = SequenceFlow(id = "sf1", sourceRef = "e1", targetRef = "t1")
                val sf2 =
                    SequenceFlow(
                        id = "sf2",
                        sourceRef = "t1",
                        targetRef = "gw1",
                        conditionExpression = "\${approved}",
                    )
                val dataObj = BpmnDataObject(id = "do1", name = "Order", collection = false)
                val dataAssoc = DataAssociation(id = "da1", sourceRef = "do1", targetRef = "t1")
                val process =
                    BpmnProcess(
                        id = "proc1",
                        name = "Order Review",
                        flowNodes = listOf(task, gateway, event),
                        sequenceFlows = listOf(sf1, sf2),
                        dataObjects = listOf(dataObj),
                        dataAssociations = listOf(dataAssoc),
                    )
                val decoded = json.decodeFromString<BpmnProcess>(json.encodeToString(process))
                decoded shouldBe process
            }

            it("round-trips a process with BpmnSubProcess, BpmnCallActivity, and MultiInstanceLoop") {
                val subProcess =
                    BpmnSubProcess(
                        id = "sp1",
                        name = "Approval Sub-Process",
                        expanded = true,
                        transactional = true,
                    )
                val callActivity =
                    BpmnCallActivity(
                        id = "ca1",
                        calledElement = "NotificationProcess",
                        loopCharacteristics = MultiInstanceLoop(sequential = false, cardinality = "3"),
                    )
                val endEvent =
                    BpmnEvent(
                        id = "e2",
                        position = EventPosition.END,
                        definition = EventDefinition.TERMINATE,
                        behaviour = EventBehaviour.THROWING,
                    )
                val sf = SequenceFlow(id = "sf3", sourceRef = "sp1", targetRef = "ca1")
                val process =
                    BpmnProcess(
                        id = "proc2",
                        name = "Approval Process",
                        flowNodes = listOf(subProcess, callActivity, endEvent),
                        sequenceFlows = listOf(sf),
                    )
                val decoded = json.decodeFromString<BpmnProcess>(json.encodeToString(process))
                decoded shouldBe process
            }
        }

        // ── BpmnModel JSON roundtrip ──────────────────────────────────────────────

        describe("BpmnModel JSON roundtrip") {
            it("round-trips a model wrapping two processes and a ProcessDiagram") {
                val taskA = BpmnTask(id = "tA", name = "Task A")
                val taskB = BpmnTask(id = "tB", name = "Task B")
                val procA = BpmnProcess(id = "procA", name = "Process A", flowNodes = listOf(taskA))
                val procB = BpmnProcess(id = "procB", name = "Process B", flowNodes = listOf(taskB))
                val diagram =
                    ProcessDiagram(
                        name = "Main View",
                        processId = "procA",
                        elementIds = listOf("tA"),
                    )
                val model =
                    BpmnModel(
                        name = "Multi-Process Model",
                        processes = listOf(procA, procB),
                        diagrams = listOf(diagram),
                    )
                val decoded = json.decodeFromString<BpmnModel>(json.encodeToString(model))
                decoded shouldBe model
            }
        }

        // ── ProcessDiagram serialization ──────────────────────────────────────────

        describe("ProcessDiagram") {
            it("round-trips with element IDs") {
                val diagram =
                    ProcessDiagram(
                        name = "Happy Path",
                        processId = "proc1",
                        elementIds = listOf("node1", "sf1"),
                    )
                val decoded = json.decodeFromString<ProcessDiagram>(json.encodeToString(diagram))
                decoded shouldBe diagram
            }

            it("round-trips with empty element IDs") {
                val diagram = ProcessDiagram(name = "Empty View", processId = "proc2")
                val decoded = json.decodeFromString<ProcessDiagram>(json.encodeToString(diagram))
                decoded.elementIds shouldBe emptyList()
            }
        }
    })
