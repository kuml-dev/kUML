package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnDataStore
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MultiInstanceLoop
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.StandardLoop
import dev.kuml.bpmn.model.TaskType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class BpmnDslTest :
    DescribeSpec({

        // ── Builder Korrektheit ───────────────────────────────────────────────────

        describe("bpmnModel top-level builder") {
            it("produces correct BpmnModel structure with process and diagram") {
                val model =
                    bpmnModel("Order Management") {
                        process(id = "orderProcess", name = "Order Process") {
                            startEvent("Start")
                            endEvent("End")
                        }
                        diagram("Happy Path", processId = "orderProcess")
                    }

                model.name shouldBe "Order Management"
                model.processes shouldHaveSize 1
                model.processes[0].id shouldBe "orderProcess"
                model.processes[0].name shouldBe "Order Process"
                model.diagrams shouldHaveSize 1
                (model.diagrams[0] as ProcessDiagram).processId shouldBe "orderProcess"
            }

            it("supports multiple processes in one model") {
                val model =
                    bpmnModel("Multi-Process") {
                        process(id = "proc1", name = "First") { startEvent() }
                        process(id = "proc2", name = "Second") { startEvent() }
                    }

                model.processes shouldHaveSize 2
                model.processes.map { it.id } shouldBe listOf("proc1", "proc2")
            }
        }

        describe("startEvent()") {
            it("produces BpmnEvent with position=START and behaviour=CATCHING") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            startEvent("Order received", definition = EventDefinition.MESSAGE)
                        }
                    }
                val event = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnEvent>()
                event.position shouldBe EventPosition.START
                event.behaviour shouldBe EventBehaviour.CATCHING
                event.definition shouldBe EventDefinition.MESSAGE
                event.name shouldBe "Order received"
            }
        }

        describe("endEvent()") {
            it("produces BpmnEvent with position=END and behaviour=THROWING") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            endEvent("Done", definition = EventDefinition.TERMINATE)
                        }
                    }
                val event = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnEvent>()
                event.position shouldBe EventPosition.END
                event.behaviour shouldBe EventBehaviour.THROWING
                event.definition shouldBe EventDefinition.TERMINATE
            }
        }

        describe("intermediateEvent()") {
            it("produces CATCHING when throwing=false") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") { intermediateEvent("Wait", throwing = false) }
                    }
                val event = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnEvent>()
                event.position shouldBe EventPosition.INTERMEDIATE
                event.behaviour shouldBe EventBehaviour.CATCHING
            }

            it("produces THROWING when throwing=true") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") { intermediateEvent("Signal", definition = EventDefinition.SIGNAL, throwing = true) }
                    }
                val event = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnEvent>()
                event.behaviour shouldBe EventBehaviour.THROWING
            }
        }

        describe("task()") {
            it("produces BpmnTask with TaskType.NONE by default") {
                val model = bpmnModel("M") { process(id = "p") { task("Review") } }
                val t = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnTask>()
                t.taskType shouldBe TaskType.NONE
                t.name shouldBe "Review"
            }

            it("produces BpmnTask with TaskType.SERVICE") {
                val model = bpmnModel("M") { process(id = "p") { task("Call API", type = TaskType.SERVICE) } }
                val t = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnTask>()
                t.taskType shouldBe TaskType.SERVICE
            }

            it("attaches StandardLoop via standardLoop { }") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            task("Retry") {
                                standardLoop(testBefore = true, condition = "\${retries < 3}")
                            }
                        }
                    }
                val t = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnTask>()
                val lc = t.loopCharacteristics.shouldNotBeNull().shouldBeInstanceOf<StandardLoop>()
                lc.testBefore.shouldBeTrue()
                lc.loopCondition shouldBe "\${retries < 3}"
            }

            it("attaches MultiInstanceLoop via multiInstance { sequential = true }") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            task("Notify each customer") {
                                multiInstance(sequential = true, cardinality = "10")
                            }
                        }
                    }
                val t = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnTask>()
                val lc = t.loopCharacteristics.shouldNotBeNull().shouldBeInstanceOf<MultiInstanceLoop>()
                lc.sequential.shouldBeTrue()
                lc.cardinality shouldBe "10"
            }
        }

        describe("gateway()") {
            it("produces BpmnGateway with GatewayType.EXCLUSIVE") {
                val model = bpmnModel("M") { process(id = "p") { gateway(GatewayType.EXCLUSIVE, name = "Check") } }
                val gw = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnGateway>()
                gw.gatewayType shouldBe GatewayType.EXCLUSIVE
                gw.name shouldBe "Check"
            }

            it("produces BpmnGateway with GatewayType.PARALLEL") {
                val model = bpmnModel("M") { process(id = "p") { gateway(GatewayType.PARALLEL) } }
                val gw = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnGateway>()
                gw.gatewayType shouldBe GatewayType.PARALLEL
            }
        }

        describe("subProcess()") {
            it("produces collapsed (expanded=false) BpmnSubProcess") {
                val model = bpmnModel("M") { process(id = "p") { subProcess("Inner", expanded = false) } }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.expanded.shouldBeFalse()
                sp.flowElements.shouldBeEmpty()
            }

            it("produces expanded BpmnSubProcess with inner flowElements") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            subProcess("Inner", expanded = true) {
                                startEvent("Inner start")
                                endEvent("Inner end")
                            }
                        }
                    }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.expanded.shouldBeTrue()
                sp.flowElements shouldHaveSize 2
            }

            it("sets triggeredByEvent=true correctly") {
                val model = bpmnModel("M") { process(id = "p") { subProcess("Event Sub", triggeredByEvent = true) } }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.triggeredByEvent.shouldBeTrue()
            }

            it("sets transactional=true correctly") {
                val model = bpmnModel("M") { process(id = "p") { subProcess("Transactional", transactional = true) } }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.transactional.shouldBeTrue()
            }
        }

        describe("callActivity()") {
            it("produces BpmnCallActivity with calledElement") {
                val model = bpmnModel("M") { process(id = "p") { callActivity("Notify", calledElement = "NotificationProcess") } }
                val ca = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnCallActivity>()
                ca.calledElement shouldBe "NotificationProcess"
                ca.name shouldBe "Notify"
            }
        }

        describe("dataObject()") {
            it("produces BpmnDataObject with collection=true") {
                val model = bpmnModel("M") { process(id = "p") { dataObject("Orders", collection = true) } }
                val dataObj = model.processes[0].dataObjects[0]
                dataObj.collection.shouldBeTrue()
                dataObj.name shouldBe "Orders"
            }
        }

        describe("boundaryEvent()") {
            it("produces INTERMEDIATE CATCHING event with attachedToRef set") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val taskId = task("Review")
                            boundaryEvent(attachedTo = taskId, definition = EventDefinition.TIMER)
                        }
                    }
                val events = model.processes[0].flowNodes.filterIsInstance<BpmnEvent>()
                val boundary = events.first { it.attachedToRef != null }
                boundary.position shouldBe EventPosition.INTERMEDIATE
                boundary.behaviour shouldBe EventBehaviour.CATCHING
                boundary.definition shouldBe EventDefinition.TIMER
                boundary.attachedToRef.shouldNotBeNull()
            }
        }

        describe("sequenceFlow()") {
            it("creates SequenceFlow with correct source and target") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val s = startEvent()
                            val e = endEvent()
                            sequenceFlow(s, e)
                        }
                    }
                val proc = model.processes[0]
                proc.sequenceFlows shouldHaveSize 1
                proc.sequenceFlows[0].sourceRef shouldBe proc.flowNodes[0].id
                proc.sequenceFlows[0].targetRef shouldBe proc.flowNodes[1].id
            }

            it("creates SequenceFlow with conditionExpression") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val gw = gateway(GatewayType.EXCLUSIVE)
                            val t = task("Execute")
                            sequenceFlow(gw, t, condition = "\${approved}")
                        }
                    }
                val flow = model.processes[0].sequenceFlows[0]
                flow.conditionExpression shouldBe "\${approved}"
            }
        }

        // ── Auto-Id-Eindeutigkeit ─────────────────────────────────────────────────

        describe("Auto-ID uniqueness") {
            it("all nodes in a process have distinct IDs") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            startEvent()
                            task("A")
                            task("B")
                            gateway(GatewayType.EXCLUSIVE)
                            endEvent()
                        }
                    }
                val ids = model.processes[0].flowNodes.map { it.id }
                ids.toSet().size shouldBe ids.size
            }

            it("two separate processes have distinct element IDs") {
                val model =
                    bpmnModel("M") {
                        process(id = "proc1") { task("T1") }
                        process(id = "proc2") { task("T2") }
                    }
                val allIds =
                    model.processes.flatMap { p ->
                        p.flowNodes.map { it.id } + p.sequenceFlows.map { it.id }
                    }
                allIds.toSet().size shouldBe allIds.size
            }

            it("IDs are deterministic — building the same model twice yields identical IDs") {
                fun buildModel() =
                    bpmnModel("M") {
                        process(id = "p") {
                            val s = startEvent()
                            val t = task("Task")
                            val e = endEvent()
                            s flowsTo t flowsTo e
                        }
                    }

                val m1 = buildModel()
                val m2 = buildModel()
                m1.processes[0].flowNodes.map { it.id } shouldBe m2.processes[0].flowNodes.map { it.id }
                m1.processes[0].sequenceFlows.map { it.id } shouldBe m2.processes[0].sequenceFlows.map { it.id }
            }

            it("SequenceFlow IDs are unique within a process") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val s = startEvent()
                            val t1 = task("T1")
                            val t2 = task("T2")
                            val e = endEvent()
                            s flowsTo t1 flowsTo t2 flowsTo e
                        }
                    }
                val flowIds = model.processes[0].sequenceFlows.map { it.id }
                flowIds.toSet().size shouldBe flowIds.size
            }

            it("node IDs contain their type prefix") {
                val model =
                    bpmnModel("M") {
                        process(id = "myProc") {
                            startEvent()
                            task("T")
                            gateway(GatewayType.PARALLEL)
                            endEvent()
                        }
                    }
                val ids = model.processes[0].flowNodes.map { it.id }
                ids.any { "start" in it }.shouldBeTrue()
                ids.any { "task" in it }.shouldBeTrue()
                ids.any { "gw" in it }.shouldBeTrue()
                ids.any { "end" in it }.shouldBeTrue()
            }
        }

        // ── Infix flowsTo ─────────────────────────────────────────────────────────

        describe("infix flowsTo") {
            it("chain start flowsTo task flowsTo end creates 2 SequenceFlows") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val s = startEvent()
                            val t = task("Step")
                            val e = endEvent()
                            s flowsTo t flowsTo e
                        }
                    }
                model.processes[0].sequenceFlows shouldHaveSize 2
            }

            it("flowsTo returns the target ID for chaining") {
                var capturedReturn: String? = null
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val s = startEvent()
                            val t = task("T")
                            capturedReturn = s flowsTo t
                            endEvent()
                        }
                    }
                capturedReturn shouldBe model.processes[0].flowNodes[1].id
            }

            it("flowsTo creates SequenceFlow without condition") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val s = startEvent()
                            val e = endEvent()
                            s flowsTo e
                        }
                    }
                model.processes[0]
                    .sequenceFlows[0]
                    .conditionExpression
                    .shouldBeNull()
            }

            it("mixing sequenceFlow() and flowsTo in the same process works") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val s = startEvent()
                            val t = task("T")
                            val gw = gateway(GatewayType.EXCLUSIVE)
                            val e = endEvent()
                            s flowsTo t
                            sequenceFlow(t, gw, condition = "\${ok}")
                            gw flowsTo e
                        }
                    }
                model.processes[0].sequenceFlows shouldHaveSize 3
                model.processes[0].sequenceFlows[1].conditionExpression shouldBe "\${ok}"
            }
        }

        // ── Verschachtelte subProcess { } ─────────────────────────────────────────

        describe("nested subProcess { }") {
            it("nested subProcess has its own flowNodes") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            subProcess("Inner", expanded = true) {
                                startEvent("Inner start")
                                task("Inner task")
                                endEvent("Inner end")
                            }
                        }
                    }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.flowElements shouldHaveSize 3
            }

            it("nested subProcess IDs are independent from parent process") {
                val model =
                    bpmnModel("M") {
                        process(id = "parent") {
                            task("Outer Task")
                            subProcess("Inner", expanded = true) {
                                task("Inner Task")
                            }
                        }
                    }
                val proc = model.processes[0]
                val outerTask = proc.flowNodes[0].shouldBeInstanceOf<BpmnTask>()
                val sp = proc.flowNodes[1].shouldBeInstanceOf<BpmnSubProcess>()
                // Inner element ID should start with the sub-process id (which is based on parent)
                sp.flowElements[0] shouldBe sp.flowElements[0] // self-check; real check: different from outer
                outerTask.id shouldBe "parent_task_1"
                sp.id shouldBe "parent_sub_2"
                sp.flowElements[0] shouldBe "${sp.id}_task_1"
            }

            it("expanded=true includes inner element IDs in flowElements") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            subProcess("Inner", expanded = true) {
                                startEvent()
                                endEvent()
                            }
                        }
                    }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.flowElements shouldHaveSize 2
                sp.flowElements.all { it.startsWith(sp.id) }.shouldBeTrue()
            }

            it("triggeredByEvent=true and transactional=true on nested subProcess are preserved") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            subProcess("Special", triggeredByEvent = true, transactional = true)
                        }
                    }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.triggeredByEvent.shouldBeTrue()
                sp.transactional.shouldBeTrue()
            }
        }

        // ── dataAssociation() ────────────────────────────────────────────────────

        describe("dataAssociation()") {
            it("produces DataAssociation in process.dataAssociations with correct sourceRef and targetRef") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val taskId = task("Process Data")
                            val doId = dataObject("Order")
                            dataAssociation(from = taskId, to = doId)
                        }
                    }
                val proc = model.processes[0]
                proc.dataAssociations shouldHaveSize 1
                val assoc = proc.dataAssociations[0]
                assoc.sourceRef shouldBe proc.flowNodes[0].id
                assoc.targetRef shouldBe proc.dataObjects[0].id
                assoc.id.startsWith("p_assoc_").shouldBeTrue()
            }

            it("dataAssociation ID uses independent data counter, not node counter") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val t = task("T")
                            val d = dataObject("D")
                            dataAssociation(t, d)
                            endEvent("End")
                        }
                    }
                val proc = model.processes[0]
                // The end event must still get nodeCounter suffix _2 (task is _1),
                // regardless of how many data elements were added.
                val bpmnEvents = proc.flowNodes.filterIsInstance<BpmnEvent>()
                val endNode = bpmnEvents.first { it.position == EventPosition.END }
                endNode.id shouldBe "p_end_2"
                // The data association gets dataCounter suffix _2 (dataObject is _1).
                proc.dataAssociations[0].id shouldBe "p_assoc_2"
            }
        }

        // ── elementById recursion into expanded subProcess ────────────────────────

        describe("BpmnProcess.elementById() with expanded subProcess") {
            it("resolves inner flow node via elementById on parent process") {
                val model =
                    bpmnModel("M") {
                        process(id = "parent") {
                            subProcess("Inner", expanded = true) {
                                startEvent("Inner start")
                                task("Inner task")
                            }
                        }
                    }
                val proc = model.processes[0]
                val sp = proc.flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                val innerStartId = sp.flowElements[0]
                val innerTaskId = sp.flowElements[1]
                proc.elementById(innerStartId).shouldNotBeNull()
                proc.elementById(innerTaskId).shouldNotBeNull()
            }

            it("flowElementNodes on BpmnSubProcess contains actual BpmnFlowNode objects") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            subProcess("Inner", expanded = true) {
                                startEvent("S")
                                endEvent("E")
                            }
                        }
                    }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.flowElementNodes shouldHaveSize 2
                sp.flowElementNodes[0].shouldBeInstanceOf<BpmnEvent>()
                sp.flowElementNodes[1].shouldBeInstanceOf<BpmnEvent>()
            }

            it("inner sequence flows are stored on BpmnSubProcess for expanded=true") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            subProcess("Inner", expanded = true) {
                                val s = startEvent()
                                val e = endEvent()
                                s flowsTo e
                            }
                        }
                    }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.innerSequenceFlows shouldHaveSize 1
            }

            it("collapsed subProcess has empty flowElementNodes and innerSequenceFlows") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            subProcess("Collapsed", expanded = false) {
                                startEvent()
                                endEvent()
                            }
                        }
                    }
                val sp = model.processes[0].flowNodes[0].shouldBeInstanceOf<BpmnSubProcess>()
                sp.flowElementNodes.shouldBeEmpty()
                sp.innerSequenceFlows.shouldBeEmpty()
            }
        }

        // ── dataStore() ───────────────────────────────────────────────────────────

        describe("dataStore()") {
            it("produces BpmnDataStore in model.dataStores with auto-generated id") {
                val model =
                    bpmnModel("M") {
                        dataStore(name = "Customer DB")
                    }
                model.dataStores shouldHaveSize 1
                val ds = model.dataStores[0].shouldBeInstanceOf<BpmnDataStore>()
                ds.id shouldBe "dataStore_1"
                ds.name shouldBe "Customer DB"
                ds.unlimited.shouldBeFalse()
            }

            it("supports explicit id and unlimited=true") {
                val model =
                    bpmnModel("M") {
                        dataStore(id = "myDs", name = "Archive", unlimited = true)
                    }
                val ds = model.dataStores[0]
                ds.id shouldBe "myDs"
                ds.unlimited.shouldBeTrue()
            }

            it("multiple dataStores get distinct auto-generated ids") {
                val model =
                    bpmnModel("M") {
                        dataStore(name = "DS1")
                        dataStore(name = "DS2")
                    }
                model.dataStores shouldHaveSize 2
                model.dataStores[0].id shouldBe "dataStore_1"
                model.dataStores[1].id shouldBe "dataStore_2"
            }

            it("dataStore is accessible via BpmnModel.elementById") {
                val model =
                    bpmnModel("M") {
                        dataStore(id = "ds1", name = "Orders")
                    }
                model.elementById("ds1").shouldNotBeNull()
            }
        }

        // ── CANCEL boundary event ─────────────────────────────────────────────────

        describe("CANCEL boundary event") {
            it("allows CANCEL definition on INTERMEDIATE boundary event attached to a transactional sub-process") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            val txSubId = subProcess("Payment", transactional = true)
                            boundaryEvent(
                                attachedTo = txSubId,
                                definition = EventDefinition.CANCEL,
                            )
                        }
                    }
                val events = model.processes[0].flowNodes.filterIsInstance<BpmnEvent>()
                val cancelBoundary = events.first { it.definition == EventDefinition.CANCEL }
                cancelBoundary.position shouldBe EventPosition.INTERMEDIATE
                cancelBoundary.behaviour shouldBe EventBehaviour.CATCHING
                cancelBoundary.attachedToRef.shouldNotBeNull()
            }

            it("still rejects CANCEL at INTERMEDIATE position without attachedToRef") {
                io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                    dev.kuml.bpmn.model.BpmnEvent(
                        id = "ev1",
                        position = EventPosition.INTERMEDIATE,
                        definition = EventDefinition.CANCEL,
                        behaviour = EventBehaviour.CATCHING,
                        attachedToRef = null,
                    )
                }
            }
        }

        // ── Diagram-Builder ───────────────────────────────────────────────────────

        describe("diagram builder") {
            it("diagram() produces ProcessDiagram with correct processId") {
                val model =
                    bpmnModel("M") {
                        process(id = "myProc") { startEvent() }
                        diagram("Overview", processId = "myProc")
                    }
                val diag = model.diagrams[0].shouldBeInstanceOf<ProcessDiagram>()
                diag.processId shouldBe "myProc"
                diag.name shouldBe "Overview"
            }

            it("diagram with include() fills elementIds") {
                var startId = ""
                var endId = ""
                val model =
                    bpmnModel("M") {
                        process(id = "p") {
                            startId = startEvent()
                            endId = endEvent()
                            startId flowsTo endId
                        }
                        diagram("Partial", processId = "p") {
                            include(startId, endId)
                        }
                    }
                val diag = model.diagrams[0].shouldBeInstanceOf<ProcessDiagram>()
                diag.elementIds shouldHaveSize 2
            }

            it("diagram without block has empty elementIds") {
                val model =
                    bpmnModel("M") {
                        process(id = "p") { startEvent() }
                        diagram("All", processId = "p")
                    }
                val diag = model.diagrams[0].shouldBeInstanceOf<ProcessDiagram>()
                diag.elementIds.shouldBeEmpty()
            }

            it("BpmnModel with multiple processes and one diagram") {
                val model =
                    bpmnModel("M") {
                        process(id = "p1") { startEvent() }
                        process(id = "p2") { endEvent() }
                        diagram("P1 View", processId = "p1")
                    }
                model.processes shouldHaveSize 2
                model.diagrams shouldHaveSize 1
                (model.diagrams[0] as ProcessDiagram).processId shouldBe "p1"
            }
        }
    })
