package dev.kuml.io.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.bpmn.model.BpmnDataStore
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.DataAssociation
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.TaskType
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a BPMN 2.0 XML document and produces a [BpmnModel].
 *
 * Uses only the JVM built-in `javax.xml` stack (no third-party XML libraries).
 * XXE protection is enabled by default.
 *
 * V3.1.7 — BPMN 2.0 XML Import
 */
public class BpmnXmlImporter {
    /** Parses BPMN 2.0 XML from a string. */
    public fun import(xml: String): BpmnModel = import(InputSource(StringReader(xml)))

    /** Parses BPMN 2.0 XML from an [InputStream]. */
    public fun import(stream: InputStream): BpmnModel = import(InputSource(stream))

    private fun import(source: InputSource): BpmnModel {
        val dbf =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // XXE hardening — prevent DOCTYPE declarations and external entity loading
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isExpandEntityReferences = false
            }
        val doc = dbf.newDocumentBuilder().parse(source)
        return parseDefinitions(doc.documentElement)
    }

    private fun parseDefinitions(root: Element): BpmnModel {
        val modelName = root.getAttribute("name").ifBlank { "Imported" }
        val processes = mutableListOf<BpmnProcess>()
        val collaborations = mutableListOf<BpmnCollaboration>()
        val dataStores = mutableListOf<BpmnDataStore>()

        root.childElements().forEach { el ->
            when (el.localName) {
                "process" -> processes += parseProcess(el)
                "collaboration" -> collaborations += parseCollaboration(el)
                "dataStore" -> dataStores += parseDataStore(el)
            }
        }

        return BpmnModel(
            name = modelName,
            processes = processes,
            collaborations = collaborations,
            dataStores = dataStores,
        )
    }

    private fun parseProcess(el: Element): BpmnProcess {
        val id = el.getAttribute("id").ifBlank { "process_1" }
        val name = el.getAttribute("name").ifBlank { null }
        val flowNodes = mutableListOf<BpmnFlowNode>()
        val sequenceFlows = mutableListOf<SequenceFlow>()
        val dataObjects = mutableListOf<BpmnDataObject>()
        val dataAssociations = mutableListOf<DataAssociation>()

        el.childElements().forEach { child ->
            when (child.localName) {
                "startEvent", "endEvent", "intermediateCatchEvent",
                "intermediateThrowEvent", "boundaryEvent",
                -> flowNodes += parseEvent(child)

                "exclusiveGateway", "inclusiveGateway", "parallelGateway",
                "eventBasedGateway", "complexGateway",
                -> flowNodes += parseGateway(child)

                "task", "userTask", "serviceTask", "sendTask", "receiveTask",
                "manualTask", "scriptTask", "businessRuleTask",
                -> flowNodes += parseTask(child)

                "subProcess" -> flowNodes += parseSubProcess(child)
                "callActivity" -> flowNodes += parseCallActivity(child)
                "sequenceFlow" -> sequenceFlows += parseSequenceFlow(child)
                "dataObject" -> dataObjects += parseDataObject(child)
                "dataInputAssociation", "dataOutputAssociation",
                "dataAssociation",
                -> dataAssociations += parseDataAssociation(child)
                // Unknown elements are silently ignored — forward-compatible
            }
        }

        return BpmnProcess(
            id = id,
            name = name,
            flowNodes = flowNodes,
            sequenceFlows = sequenceFlows,
            dataObjects = dataObjects,
            dataAssociations = dataAssociations,
        )
    }

    private fun parseEvent(el: Element): BpmnEvent {
        val id = el.getAttribute("id").ifBlank { "event_1" }
        val name = el.getAttribute("name").ifBlank { null }
        val attachedToRef = el.getAttribute("attachedToRef").ifBlank { null }
        val cancelActivity = el.getAttribute("cancelActivity")
        val interrupting = cancelActivity.isBlank() || cancelActivity == "true"

        val position =
            when (el.localName) {
                "startEvent" -> EventPosition.START
                "endEvent" -> EventPosition.END
                "boundaryEvent" -> EventPosition.INTERMEDIATE
                else -> EventPosition.INTERMEDIATE
            }

        val behaviour =
            when {
                position == EventPosition.END -> EventBehaviour.THROWING
                el.localName == "intermediateThrowEvent" -> EventBehaviour.THROWING
                else -> EventBehaviour.CATCHING
            }

        val definition =
            el
                .childElements()
                .firstOrNull { it.localName?.endsWith("EventDefinition") == true }
                ?.let { parseEventDefinition(it.localName) }
                ?: EventDefinition.NONE

        // Boundary events: CANCEL is only valid as boundary (INTERMEDIATE + attachedToRef)
        // For BpmnEvent init constraints, boundary events must have attachedToRef set
        val resolvedAttachedToRef =
            if (el.localName == "boundaryEvent") {
                attachedToRef ?: "unknown"
            } else {
                attachedToRef
            }

        return BpmnEvent(
            id = id,
            name = name,
            position = position,
            definition = definition,
            behaviour = behaviour,
            interrupting = interrupting,
            attachedToRef = resolvedAttachedToRef,
        )
    }

    private fun parseEventDefinition(tag: String): EventDefinition =
        when (tag) {
            "messageEventDefinition" -> EventDefinition.MESSAGE
            "timerEventDefinition" -> EventDefinition.TIMER
            "errorEventDefinition" -> EventDefinition.ERROR
            "escalationEventDefinition" -> EventDefinition.ESCALATION
            "signalEventDefinition" -> EventDefinition.SIGNAL
            "compensateEventDefinition" -> EventDefinition.COMPENSATION
            "conditionalEventDefinition" -> EventDefinition.CONDITIONAL
            "linkEventDefinition" -> EventDefinition.LINK
            "cancelEventDefinition" -> EventDefinition.CANCEL
            "terminateEventDefinition" -> EventDefinition.TERMINATE
            "multipleEventDefinition" -> EventDefinition.MULTIPLE
            "parallelMultipleEventDefinition" -> EventDefinition.PARALLEL_MULTIPLE
            else -> EventDefinition.NONE
        }

    private fun parseGateway(el: Element): BpmnGateway {
        val gatewayType =
            when (el.localName) {
                "exclusiveGateway" -> GatewayType.EXCLUSIVE
                "inclusiveGateway" -> GatewayType.INCLUSIVE
                "parallelGateway" -> GatewayType.PARALLEL
                "eventBasedGateway" -> GatewayType.EVENT_BASED
                else -> GatewayType.COMPLEX
            }
        return BpmnGateway(
            id = el.getAttribute("id").ifBlank { "gw_1" },
            name = el.getAttribute("name").ifBlank { null },
            gatewayType = gatewayType,
            defaultFlow = el.getAttribute("default").ifBlank { null },
        )
    }

    private fun parseTask(el: Element): BpmnTask {
        val taskType =
            when (el.localName) {
                "userTask" -> TaskType.USER
                "serviceTask" -> TaskType.SERVICE
                "sendTask" -> TaskType.SEND
                "receiveTask" -> TaskType.RECEIVE
                "manualTask" -> TaskType.MANUAL
                "scriptTask" -> TaskType.SCRIPT
                "businessRuleTask" -> TaskType.BUSINESS_RULE
                else -> TaskType.NONE
            }
        return BpmnTask(
            id = el.getAttribute("id").ifBlank { "task_1" },
            name = el.getAttribute("name").ifBlank { null },
            taskType = taskType,
        )
    }

    private fun parseSubProcess(el: Element): BpmnSubProcess {
        val innerProcess = parseProcess(el)
        return BpmnSubProcess(
            id = el.getAttribute("id").ifBlank { "sp_1" },
            name = el.getAttribute("name").ifBlank { null },
            expanded = true,
            triggeredByEvent = el.getAttribute("triggeredByEvent") == "true",
            flowElementNodes = innerProcess.flowNodes,
            innerSequenceFlows = innerProcess.sequenceFlows,
            innerDataObjects = innerProcess.dataObjects,
            innerDataAssociations = innerProcess.dataAssociations,
        )
    }

    private fun parseCallActivity(el: Element): BpmnCallActivity =
        BpmnCallActivity(
            id = el.getAttribute("id").ifBlank { "ca_1" },
            name = el.getAttribute("name").ifBlank { null },
            calledElement = el.getAttribute("calledElement").ifBlank { null },
        )

    private fun parseSequenceFlow(el: Element): SequenceFlow =
        SequenceFlow(
            id = el.getAttribute("id").ifBlank { "sf_1" },
            name = el.getAttribute("name").ifBlank { null },
            sourceRef = el.getAttribute("sourceRef"),
            targetRef = el.getAttribute("targetRef"),
            isDefault = el.getAttribute("isDefault") == "true",
            conditionExpression =
                el
                    .childElements()
                    .firstOrNull { it.localName == "conditionExpression" }
                    ?.textContent
                    ?.trim()
                    ?.ifBlank { null },
        )

    private fun parseDataObject(el: Element): BpmnDataObject =
        BpmnDataObject(
            id = el.getAttribute("id").ifBlank { "do_1" },
            name = el.getAttribute("name").ifBlank { null },
            collection = el.getAttribute("isCollection") == "true",
        )

    private fun parseDataStore(el: Element): BpmnDataStore =
        BpmnDataStore(
            id = el.getAttribute("id").ifBlank { "ds_1" },
            name = el.getAttribute("name").ifBlank { null },
        )

    private fun parseDataAssociation(el: Element): DataAssociation =
        DataAssociation(
            id = el.getAttribute("id").ifBlank { "da_1" },
            sourceRef = el.getAttribute("sourceRef"),
            targetRef = el.getAttribute("targetRef"),
            name = el.getAttribute("name").ifBlank { null },
        )

    private fun parseCollaboration(el: Element): BpmnCollaboration {
        val participants = mutableListOf<BpmnParticipant>()
        val messageFlows = mutableListOf<MessageFlow>()

        el.childElements().forEach { child ->
            when (child.localName) {
                "participant" ->
                    participants +=
                        BpmnParticipant(
                            id = child.getAttribute("id").ifBlank { "p_1" },
                            name = child.getAttribute("name").ifBlank { null },
                            processRef = child.getAttribute("processRef").ifBlank { null },
                            lanes =
                                child
                                    .childElements()
                                    .filter { it.localName == "laneSet" }
                                    .flatMap { it.childElements().filter { c -> c.localName == "lane" } }
                                    .map { parseLane(it) },
                        )
                "messageFlow" ->
                    messageFlows +=
                        MessageFlow(
                            id = child.getAttribute("id").ifBlank { "mf_1" },
                            name = child.getAttribute("name").ifBlank { null },
                            sourceRef = child.getAttribute("sourceRef"),
                            targetRef = child.getAttribute("targetRef"),
                        )
                // Unknown elements are silently ignored
            }
        }

        return BpmnCollaboration(
            id = el.getAttribute("id").ifBlank { "collab_1" },
            name = el.getAttribute("name").ifBlank { null },
            participants = participants,
            messageFlows = messageFlows,
        )
    }

    private fun parseLane(el: Element): BpmnLane =
        BpmnLane(
            id = el.getAttribute("id").ifBlank { "lane_1" },
            name = el.getAttribute("name").ifBlank { null },
            flowNodeRefs =
                el
                    .childElements()
                    .filter { it.localName == "flowNodeRef" }
                    .map { it.textContent.trim() },
            childLanes =
                el
                    .childElements()
                    .filter { it.localName == "lane" }
                    .map { parseLane(it) },
        )

    private fun Element.childElements(): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) result += child
        }
        return result
    }
}
