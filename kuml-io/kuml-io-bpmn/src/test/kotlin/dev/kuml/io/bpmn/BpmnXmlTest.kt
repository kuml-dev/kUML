package dev.kuml.io.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.bpmn.model.BpmnDataStore
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.TaskType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class BpmnXmlTest :
    FreeSpec({

        // ─── helpers ───────────────────────────────────────────────────────────────

        fun emptyModel(name: String = "Test") = BpmnModel(name = name)

        fun processModel(
            vararg nodes: dev.kuml.bpmn.model.BpmnFlowNode,
            flows: List<SequenceFlow> = emptyList(),
        ) = BpmnModel(
            name = "ProcessModel",
            processes =
                listOf(
                    BpmnProcess(id = "proc_1", flowNodes = nodes.toList(), sequenceFlows = flows),
                ),
        )

        fun startEvent(
            id: String = "start_1",
            name: String? = null,
            def: EventDefinition = EventDefinition.NONE,
        ) = BpmnEvent(id = id, name = name, position = EventPosition.START, definition = def, behaviour = EventBehaviour.CATCHING)

        fun endEvent(
            id: String = "end_1",
            name: String? = null,
            def: EventDefinition = EventDefinition.NONE,
        ) = BpmnEvent(id = id, name = name, position = EventPosition.END, definition = def, behaviour = EventBehaviour.THROWING)

        fun intermediateEvent(
            id: String,
            def: EventDefinition = EventDefinition.NONE,
        ) = BpmnEvent(id = id, position = EventPosition.INTERMEDIATE, definition = def, behaviour = EventBehaviour.CATCHING)

        fun boundaryEvent(
            id: String,
            attachedTo: String,
            def: EventDefinition = EventDefinition.NONE,
            interrupting: Boolean = true,
        ) = BpmnEvent(
            id = id,
            position = EventPosition.INTERMEDIATE,
            definition = def,
            behaviour = EventBehaviour.CATCHING,
            attachedToRef = attachedTo,
            interrupting = interrupting,
        )

        fun seqFlow(
            id: String,
            source: String,
            target: String,
            condition: String? = null,
            default: Boolean = false,
        ) = SequenceFlow(id = id, sourceRef = source, targetRef = target, conditionExpression = condition, isDefault = default)

        // ─── Export tests ──────────────────────────────────────────────────────────

        "Export" - {

            "empty BpmnModel produces valid XML declaration" {
                val xml = BpmnXml.export(emptyModel())
                xml shouldStartWith "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            }

            "empty BpmnModel has definitions root element" {
                val xml = BpmnXml.export(emptyModel())
                xml shouldContain "<definitions"
                xml shouldContain "http://www.omg.org/spec/BPMN/20100524/MODEL"
                xml shouldContain "</definitions>"
            }

            "StartEvent NONE produces self-closing tag" {
                val xml = BpmnXml.export(processModel(startEvent("s1", "Start")))
                xml shouldContain """<startEvent id="s1" name="Start"/>"""
            }

            "StartEvent MESSAGE produces messageEventDefinition child" {
                val xml = BpmnXml.export(processModel(startEvent("s1", def = EventDefinition.MESSAGE)))
                xml shouldContain "<startEvent"
                xml shouldContain "<messageEventDefinition/>"
                xml shouldContain "</startEvent>"
            }

            "EndEvent TERMINATE produces terminateEventDefinition child" {
                val xml = BpmnXml.export(processModel(endEvent("e1", def = EventDefinition.TERMINATE)))
                xml shouldContain "<endEvent"
                xml shouldContain "<terminateEventDefinition/>"
                xml shouldContain "</endEvent>"
            }

            "BoundaryEvent has attachedToRef and cancelActivity attributes" {
                val xml = BpmnXml.export(processModel(boundaryEvent("b1", "task_1", interrupting = false)))
                xml shouldContain "attachedToRef=\"task_1\""
                xml shouldContain "cancelActivity=\"false\""
            }

            "IntermediateThrowEvent with MESSAGE produces intermediateThrowEvent tag" {
                val event =
                    BpmnEvent(
                        id = "ite1",
                        name = "Throw Message",
                        position = EventPosition.INTERMEDIATE,
                        definition = EventDefinition.MESSAGE,
                        behaviour = EventBehaviour.THROWING,
                    )
                val xml = BpmnXml.export(processModel(event))
                xml shouldContain "<intermediateThrowEvent"
                xml shouldContain """id="ite1""""
                xml shouldContain "<messageEventDefinition/>"
                xml shouldContain "</intermediateThrowEvent>"
                xml shouldNotContain "<intermediateCatchEvent"
            }

            "IntermediateCatchEvent with TIMER produces intermediateCatchEvent tag" {
                val event =
                    BpmnEvent(
                        id = "ice1",
                        position = EventPosition.INTERMEDIATE,
                        definition = EventDefinition.TIMER,
                        behaviour = EventBehaviour.CATCHING,
                    )
                val xml = BpmnXml.export(processModel(event))
                xml shouldContain "<intermediateCatchEvent"
                xml shouldNotContain "<intermediateThrowEvent"
            }

            "ExclusiveGateway produces exclusiveGateway tag" {
                val gw = BpmnGateway(id = "gw1", gatewayType = GatewayType.EXCLUSIVE)
                val xml = BpmnXml.export(processModel(gw))
                xml shouldContain """<exclusiveGateway id="gw1"/>"""
            }

            "ParallelGateway produces parallelGateway tag" {
                val gw = BpmnGateway(id = "gw2", gatewayType = GatewayType.PARALLEL)
                val xml = BpmnXml.export(processModel(gw))
                xml shouldContain """<parallelGateway id="gw2"/>"""
            }

            "UserTask produces userTask tag" {
                val task = BpmnTask(id = "t1", name = "Review", taskType = TaskType.USER)
                val xml = BpmnXml.export(processModel(task))
                xml shouldContain """<userTask id="t1" name="Review"/>"""
            }

            "ServiceTask produces serviceTask tag" {
                val task = BpmnTask(id = "t2", taskType = TaskType.SERVICE)
                val xml = BpmnXml.export(processModel(task))
                xml shouldContain """<serviceTask id="t2"/>"""
            }

            "SequenceFlow without condition is self-closing" {
                val model =
                    BpmnModel(
                        name = "M",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "p1",
                                    flowNodes = listOf(startEvent("s1"), endEvent("e1")),
                                    sequenceFlows = listOf(seqFlow("sf1", "s1", "e1")),
                                ),
                            ),
                    )
                val xml = BpmnXml.export(model)
                xml shouldContain """<sequenceFlow id="sf1" sourceRef="s1" targetRef="e1"/>"""
            }

            "SequenceFlow with condition produces conditionExpression child" {
                val model =
                    BpmnModel(
                        name = "M",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "p1",
                                    flowNodes = listOf(startEvent("s1"), endEvent("e1")),
                                    sequenceFlows = listOf(seqFlow("sf1", "s1", "e1", condition = "\${amount > 100}")),
                                ),
                            ),
                    )
                val xml = BpmnXml.export(model)
                xml shouldContain "<conditionExpression>"
                // xmlText escapes '>' to '&gt;' — valid XML text escaping
                xml shouldContain "\${amount &gt; 100}"
            }

            "SequenceFlow isDefault=true includes isDefault attribute" {
                val model =
                    BpmnModel(
                        name = "M",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "p1",
                                    flowNodes = listOf(startEvent("s1"), endEvent("e1")),
                                    sequenceFlows = listOf(seqFlow("sf1", "s1", "e1", default = true)),
                                ),
                            ),
                    )
                val xml = BpmnXml.export(model)
                xml shouldContain """isDefault="true""""
            }

            "SubProcess wraps inner elements" {
                val inner =
                    BpmnSubProcess(
                        id = "sp1",
                        name = "Inner",
                        expanded = true,
                        flowElementNodes = listOf(startEvent("is1"), endEvent("ie1")),
                        innerSequenceFlows = listOf(seqFlow("isf1", "is1", "ie1")),
                    )
                val xml = BpmnXml.export(processModel(inner))
                xml shouldContain "<subProcess"
                xml shouldContain "</subProcess>"
                xml shouldContain """id="sp1""""
                xml shouldContain """id="is1""""
            }

            "CallActivity with calledElement attribute" {
                val ca = BpmnCallActivity(id = "ca1", name = "Invoke", calledElement = "globalProcess")
                val xml = BpmnXml.export(processModel(ca))
                xml shouldContain """<callActivity id="ca1" name="Invoke" calledElement="globalProcess"/>"""
            }

            "DataObject in process" {
                val model =
                    BpmnModel(
                        name = "M",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "p1",
                                    dataObjects = listOf(BpmnDataObject(id = "do1", name = "Invoice")),
                                ),
                            ),
                    )
                val xml = BpmnXml.export(model)
                xml shouldContain """<dataObject id="do1" name="Invoice"/>"""
            }

            "DataStore at model level in definitions" {
                val model =
                    BpmnModel(
                        name = "M",
                        dataStores = listOf(BpmnDataStore(id = "ds1", name = "CRM")),
                    )
                val xml = BpmnXml.export(model)
                xml shouldContain """<dataStore id="ds1" name="CRM"/>"""
            }

            "Collaboration produces collaboration, participant and messageFlow elements" {
                val model =
                    BpmnModel(
                        name = "CollabModel",
                        collaborations =
                            listOf(
                                BpmnCollaboration(
                                    id = "collab1",
                                    participants =
                                        listOf(
                                            BpmnParticipant(id = "p1", name = "Customer", processRef = "proc_1"),
                                            BpmnParticipant(id = "p2", name = "Bank"),
                                        ),
                                    messageFlows =
                                        listOf(
                                            MessageFlow(id = "mf1", sourceRef = "p1", targetRef = "p2"),
                                        ),
                                ),
                            ),
                    )
                val xml = BpmnXml.export(model)
                xml shouldContain "<collaboration"
                xml shouldContain "</collaboration>"
                xml shouldContain """<participant id="p1" name="Customer" processRef="proc_1"/>"""
                xml shouldContain """<participant id="p2" name="Bank"/>"""
                xml shouldContain """<messageFlow id="mf1" sourceRef="p1" targetRef="p2"/>"""
            }

            "XSS: element name with angle brackets is escaped in XML attribute" {
                val task = BpmnTask(id = "t1", name = "<script>alert('xss')</script>", taskType = TaskType.NONE)
                val xml = BpmnXml.export(processModel(task))
                xml shouldContain "&lt;script&gt;"
                xml shouldNotContain "<script>"
            }

            "XML attribute with double-quotes is encoded as &quot;" {
                val task = BpmnTask(id = "t1", name = "Say \"Hello\"", taskType = TaskType.NONE)
                val xml = BpmnXml.export(processModel(task))
                xml shouldContain "&quot;Hello&quot;"
            }

            "Ampersand in name is encoded as &amp;" {
                val task = BpmnTask(id = "t1", name = "R&D", taskType = TaskType.NONE)
                val xml = BpmnXml.export(processModel(task))
                xml shouldContain "R&amp;D"
            }
        }

        // ─── Import tests ──────────────────────────────────────────────────────────

        "Import" - {

            val minimalBpmn =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             name="MinimalTest"
                             targetNamespace="http://example.com">
                </definitions>
                """.trimIndent()

            "minimal BPMN with no processes returns empty model without crash" {
                val model = BpmnXml.import(minimalBpmn)
                model.processes shouldHaveSize 0
                model.collaborations shouldHaveSize 0
            }

            "model name is read from definitions element" {
                val model = BpmnXml.import(minimalBpmn)
                model.name shouldBe "MinimalTest"
            }

            "process with StartEvent EndEvent and SequenceFlow is parsed correctly" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="P" targetNamespace="http://x">
                      <process id="proc1" name="Main">
                        <startEvent id="s1" name="Start"/>
                        <endEvent id="e1" name="End"/>
                        <sequenceFlow id="sf1" sourceRef="s1" targetRef="e1"/>
                      </process>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                model.processes shouldHaveSize 1
                val proc = model.processes[0]
                proc.id shouldBe "proc1"
                proc.flowNodes shouldHaveSize 2
                proc.sequenceFlows shouldHaveSize 1
                val start = proc.flowNodes.filterIsInstance<BpmnEvent>().first { it.position == EventPosition.START }
                start.name shouldBe "Start"
                proc.sequenceFlows[0].sourceRef shouldBe "s1"
                proc.sequenceFlows[0].targetRef shouldBe "e1"
            }

            "ExclusiveGateway with defaultFlow is parsed correctly" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="G" targetNamespace="http://x">
                      <process id="proc1">
                        <exclusiveGateway id="gw1" name="Decision" default="sf2"/>
                      </process>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                val gw =
                    model.processes[0]
                        .flowNodes
                        .filterIsInstance<BpmnGateway>()
                        .first()
                gw.gatewayType shouldBe GatewayType.EXCLUSIVE
                gw.defaultFlow shouldBe "sf2"
                gw.name shouldBe "Decision"
            }

            "ConditionExpression is extracted from sequenceFlow child element" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="C" targetNamespace="http://x">
                      <process id="proc1">
                        <sequenceFlow id="sf1" sourceRef="s1" targetRef="e1">
                          <conditionExpression>${'$'}{amount > 100}</conditionExpression>
                        </sequenceFlow>
                      </process>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                val sf = model.processes[0].sequenceFlows[0]
                sf.conditionExpression shouldBe "\${amount > 100}"
            }

            "Collaboration with 2 participants and MessageFlow is parsed correctly" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="Col" targetNamespace="http://x">
                      <collaboration id="collab1" name="Order Process">
                        <participant id="p1" name="Customer" processRef="proc1"/>
                        <participant id="p2" name="Supplier"/>
                        <messageFlow id="mf1" sourceRef="p1" targetRef="p2"/>
                      </collaboration>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                model.collaborations shouldHaveSize 1
                val collab = model.collaborations[0]
                collab.id shouldBe "collab1"
                collab.name shouldBe "Order Process"
                collab.participants shouldHaveSize 2
                collab.participants[0].processRef shouldBe "proc1"
                collab.participants[1].processRef.shouldBeNull()
                collab.messageFlows shouldHaveSize 1
                collab.messageFlows[0].sourceRef shouldBe "p1"
            }

            "Unknown XML elements in process are silently ignored" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="U" targetNamespace="http://x">
                      <process id="proc1">
                        <startEvent id="s1"/>
                        <unknownElement id="foo" someAttr="bar"/>
                        <endEvent id="e1"/>
                      </process>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                model.processes[0].flowNodes shouldHaveSize 2
            }

            "XXE injection attempt throws an exception" {
                val xxeXml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="XXE" targetNamespace="http://x">
                      <process id="p1">
                        <startEvent id="s1" name="&xxe;"/>
                      </process>
                    </definitions>
                    """.trimIndent()
                shouldThrow<Exception> {
                    BpmnXml.import(xxeXml)
                }
            }

            "BoundaryEvent with cancelActivity=false has interrupting=false" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="B" targetNamespace="http://x">
                      <process id="proc1">
                        <userTask id="t1"/>
                        <boundaryEvent id="b1" attachedToRef="t1" cancelActivity="false">
                          <timerEventDefinition/>
                        </boundaryEvent>
                      </process>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                val boundary =
                    model.processes[0]
                        .flowNodes
                        .filterIsInstance<BpmnEvent>()
                        .first { it.attachedToRef != null }
                boundary.interrupting.shouldBeFalse()
                boundary.definition shouldBe EventDefinition.TIMER
                boundary.position shouldBe EventPosition.INTERMEDIATE
            }

            "StartEvent with timerEventDefinition has definition TIMER" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="T" targetNamespace="http://x">
                      <process id="proc1">
                        <startEvent id="s1">
                          <timerEventDefinition/>
                        </startEvent>
                      </process>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                val start =
                    model.processes[0]
                        .flowNodes
                        .filterIsInstance<BpmnEvent>()
                        .first()
                start.definition shouldBe EventDefinition.TIMER
            }

            "isDefault=true on sequenceFlow is parsed" {
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="D" targetNamespace="http://x">
                      <process id="proc1">
                        <sequenceFlow id="sf1" sourceRef="s1" targetRef="e1" isDefault="true"/>
                      </process>
                    </definitions>
                    """.trimIndent()
                val model = BpmnXml.import(xml)
                model.processes[0]
                    .sequenceFlows[0]
                    .isDefault
                    .shouldBeTrue()
            }

            "all task types are parsed correctly" {
                val taskTags =
                    listOf(
                        "task" to TaskType.NONE,
                        "userTask" to TaskType.USER,
                        "serviceTask" to TaskType.SERVICE,
                        "sendTask" to TaskType.SEND,
                        "receiveTask" to TaskType.RECEIVE,
                        "manualTask" to TaskType.MANUAL,
                        "scriptTask" to TaskType.SCRIPT,
                        "businessRuleTask" to TaskType.BUSINESS_RULE,
                    )
                taskTags.forEach { (tag, expected) ->
                    val xml =
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="Tasks" targetNamespace="http://x">
                          <process id="proc1">
                            <$tag id="t1"/>
                          </process>
                        </definitions>
                        """.trimIndent()
                    val task =
                        BpmnXml
                            .import(xml)
                            .processes[0]
                            .flowNodes
                            .filterIsInstance<BpmnTask>()
                            .first()
                    task.taskType shouldBe expected
                }
            }
        }

        // ─── Roundtrip tests ───────────────────────────────────────────────────────

        "Roundtrip" - {

            "process with all FlowNode types survives export-import roundtrip" {
                val original =
                    BpmnModel(
                        name = "RoundtripAll",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "proc1",
                                    name = "Full Process",
                                    flowNodes =
                                        listOf(
                                            startEvent("s1", "Start"),
                                            BpmnTask(id = "t1", name = "Do Work", taskType = TaskType.USER),
                                            BpmnTask(id = "t2", name = "Notify", taskType = TaskType.SERVICE),
                                            BpmnGateway(id = "gw1", name = "Decision", gatewayType = GatewayType.EXCLUSIVE),
                                            BpmnGateway(id = "gw2", gatewayType = GatewayType.PARALLEL),
                                            BpmnCallActivity(id = "ca1", name = "Invoke Sub", calledElement = "subProc"),
                                            endEvent("e1", "End"),
                                        ),
                                    sequenceFlows =
                                        listOf(
                                            seqFlow("sf1", "s1", "t1"),
                                            seqFlow("sf2", "t1", "gw1"),
                                            seqFlow("sf3", "gw1", "t2", condition = "\${ok}"),
                                            seqFlow("sf4", "gw1", "ca1", default = true),
                                            seqFlow("sf5", "t2", "gw2"),
                                            seqFlow("sf6", "ca1", "gw2"),
                                            seqFlow("sf7", "gw2", "e1"),
                                        ),
                                ),
                            ),
                    )

                val xml = BpmnXml.export(original)
                val restored = BpmnXml.import(xml)

                restored.name shouldBe original.name
                restored.processes shouldHaveSize 1
                val proc = restored.processes[0]
                proc.id shouldBe "proc1"
                proc.name shouldBe "Full Process"
                proc.flowNodes shouldHaveSize 7
                proc.sequenceFlows shouldHaveSize 7

                val tasks = proc.flowNodes.filterIsInstance<BpmnTask>()
                tasks shouldHaveSize 2
                tasks.first { it.id == "t1" }.taskType shouldBe TaskType.USER
                tasks.first { it.id == "t2" }.taskType shouldBe TaskType.SERVICE

                val gateways = proc.flowNodes.filterIsInstance<BpmnGateway>()
                gateways shouldHaveSize 2
                gateways.first { it.id == "gw1" }.gatewayType shouldBe GatewayType.EXCLUSIVE

                val calls = proc.flowNodes.filterIsInstance<BpmnCallActivity>()
                calls.first().calledElement shouldBe "subProc"

                val condFlow = proc.sequenceFlows.first { it.id == "sf3" }
                condFlow.conditionExpression shouldBe "\${ok}"

                val defaultFlow = proc.sequenceFlows.first { it.id == "sf4" }
                defaultFlow.isDefault.shouldBeTrue()
            }

            "Collaboration roundtrip preserves participants and messageFlows" {
                val original =
                    BpmnModel(
                        name = "CollabRoundtrip",
                        collaborations =
                            listOf(
                                BpmnCollaboration(
                                    id = "collab1",
                                    name = "B2B Process",
                                    participants =
                                        listOf(
                                            BpmnParticipant(id = "p1", name = "Buyer", processRef = "proc1"),
                                            BpmnParticipant(id = "p2", name = "Seller"),
                                        ),
                                    messageFlows =
                                        listOf(
                                            MessageFlow(id = "mf1", name = "Order Request", sourceRef = "p1", targetRef = "p2"),
                                            MessageFlow(id = "mf2", sourceRef = "p2", targetRef = "p1"),
                                        ),
                                ),
                            ),
                    )

                val xml = BpmnXml.export(original)
                val restored = BpmnXml.import(xml)

                restored.collaborations shouldHaveSize 1
                val collab = restored.collaborations[0]
                collab.id shouldBe "collab1"
                collab.name shouldBe "B2B Process"
                collab.participants shouldHaveSize 2
                collab.participants.first { it.id == "p1" }.processRef shouldBe "proc1"
                collab.participants
                    .first { it.id == "p2" }
                    .processRef
                    .shouldBeNull()
                collab.messageFlows shouldHaveSize 2
                collab.messageFlows.first { it.id == "mf1" }.name shouldBe "Order Request"
            }

            "ConditionExpression roundtrip preserves expression text" {
                val original =
                    BpmnModel(
                        name = "CondRoundtrip",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "proc1",
                                    sequenceFlows =
                                        listOf(
                                            SequenceFlow(
                                                id = "sf1",
                                                sourceRef = "s1",
                                                targetRef = "e1",
                                                conditionExpression = "\${amount > 1000 && status == 'APPROVED'}",
                                            ),
                                        ),
                                ),
                            ),
                    )

                val xml = BpmnXml.export(original)
                val restored = BpmnXml.import(xml)

                val sf = restored.processes[0].sequenceFlows[0]
                sf.conditionExpression shouldBe "\${amount > 1000 && status == 'APPROVED'}"
            }

            "IntermediateThrowEvent roundtrip preserves position, behaviour and definition" {
                val original =
                    BpmnModel(
                        name = "ThrowRoundtrip",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "proc1",
                                    flowNodes =
                                        listOf(
                                            BpmnEvent(
                                                id = "ite1",
                                                name = "Escalate",
                                                position = EventPosition.INTERMEDIATE,
                                                definition = EventDefinition.ESCALATION,
                                                behaviour = EventBehaviour.THROWING,
                                            ),
                                        ),
                                ),
                            ),
                    )
                val xml = BpmnXml.export(original)
                val restored = BpmnXml.import(xml)
                val event =
                    restored.processes[0]
                        .flowNodes
                        .filterIsInstance<BpmnEvent>()
                        .first()
                event.id shouldBe "ite1"
                event.name shouldBe "Escalate"
                event.position shouldBe EventPosition.INTERMEDIATE
                event.behaviour shouldBe EventBehaviour.THROWING
                event.definition shouldBe EventDefinition.ESCALATION
            }

            "BoundaryEvent roundtrip preserves INTERMEDIATE position" {
                val original =
                    BpmnModel(
                        name = "BoundaryRoundtrip",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "proc1",
                                    flowNodes =
                                        listOf(
                                            BpmnTask(id = "t1", taskType = TaskType.USER),
                                            BpmnEvent(
                                                id = "b1",
                                                position = EventPosition.INTERMEDIATE,
                                                definition = EventDefinition.TIMER,
                                                behaviour = EventBehaviour.CATCHING,
                                                attachedToRef = "t1",
                                                interrupting = false,
                                            ),
                                        ),
                                ),
                            ),
                    )
                val xml = BpmnXml.export(original)
                val restored = BpmnXml.import(xml)
                val boundary =
                    restored.processes[0]
                        .flowNodes
                        .filterIsInstance<BpmnEvent>()
                        .first { it.attachedToRef != null }
                boundary.position shouldBe EventPosition.INTERMEDIATE
                boundary.attachedToRef shouldBe "t1"
                boundary.interrupting.shouldBeFalse()
            }

            "DataObject roundtrip preserves id and name" {
                val original =
                    BpmnModel(
                        name = "DataRoundtrip",
                        processes =
                            listOf(
                                BpmnProcess(
                                    id = "proc1",
                                    dataObjects =
                                        listOf(
                                            BpmnDataObject(id = "do1", name = "Invoice Data"),
                                        ),
                                ),
                            ),
                    )

                val xml = BpmnXml.export(original)
                val restored = BpmnXml.import(xml)

                val dos = restored.processes[0].dataObjects
                dos shouldHaveSize 1
                dos[0].id shouldBe "do1"
                dos[0].name shouldBe "Invoice Data"
            }
        }
    })
