package dev.kuml.io.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.bpmn.model.BpmnDataStore
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
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

/**
 * Serializes a [BpmnModel] to a valid BPMN 2.0 XML string.
 *
 * Uses the OMG BPMN namespace `http://www.omg.org/spec/BPMN/20100524/MODEL`.
 * No external dependencies — pure Kotlin stdlib with [StringBuilder].
 *
 * V3.1.7 — BPMN 2.0 XML Export
 */
public class BpmnXmlExporter {
    /** Exports [model] to a BPMN 2.0 XML string. */
    public fun export(model: BpmnModel): String =
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"""")
            appendLine()
            append("""             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
            appendLine()
            append("""             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"""")
            appendLine()
            append("""             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"""")
            appendLine()
            append("""             targetNamespace="http://kuml.dev/bpmn"""")
            appendLine()
            append("""             name=""")
            append(xmlAttr(model.name))
            appendLine(">")

            // Root-level data stores (BPMN 2.0: DataStore is a RootElement under Definitions)
            model.dataStores.forEach { ds -> appendDataStore(ds, this, indent = "  ") }
            model.processes.forEach { process -> appendProcess(process, this) }
            model.collaborations.forEach { collab -> appendCollaboration(collab, this) }

            appendLine("</definitions>")
        }

    private fun appendProcess(
        process: BpmnProcess,
        sb: StringBuilder,
    ) {
        sb.append("""  <process id=""")
        sb.append(xmlAttr(process.id))
        process.name?.let {
            sb.append(""" name=""")
            sb.append(xmlAttr(it))
        }
        sb.appendLine(">")

        process.flowNodes.forEach { node -> appendFlowNode(node, sb, indent = "    ") }
        process.sequenceFlows.forEach { flow -> appendSequenceFlow(flow, sb, indent = "    ") }
        process.dataObjects.forEach { obj -> appendDataObject(obj, sb, indent = "    ") }
        process.dataAssociations.forEach { da ->
            sb.append("""    <dataAssociation id=""")
            sb.append(xmlAttr(da.id))
            da.name?.let {
                sb.append(""" name=""")
                sb.append(xmlAttr(it))
            }
            sb.append(""" sourceRef=""")
            sb.append(xmlAttr(da.sourceRef))
            sb.append(""" targetRef=""")
            sb.append(xmlAttr(da.targetRef))
            sb.appendLine("/>")
        }

        sb.appendLine("  </process>")
    }

    private fun appendFlowNode(
        node: BpmnFlowNode,
        sb: StringBuilder,
        indent: String,
    ) {
        when (node) {
            is BpmnEvent -> appendEvent(node, sb, indent)
            is BpmnGateway -> appendGateway(node, sb, indent)
            is BpmnTask -> appendTask(node, sb, indent)
            is BpmnSubProcess -> appendSubProcess(node, sb, indent)
            is BpmnCallActivity -> appendCallActivity(node, sb, indent)
        }
    }

    private fun appendEvent(
        event: BpmnEvent,
        sb: StringBuilder,
        indent: String,
    ) {
        val tag =
            when (event.position) {
                EventPosition.START -> if (event.attachedToRef != null) "boundaryEvent" else "startEvent"
                EventPosition.INTERMEDIATE ->
                    when {
                        event.attachedToRef != null -> "boundaryEvent"
                        event.behaviour == EventBehaviour.THROWING -> "intermediateThrowEvent"
                        else -> "intermediateCatchEvent"
                    }
                EventPosition.END -> "endEvent"
            }
        sb.append("$indent<$tag id=")
        sb.append(xmlAttr(event.id))
        event.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        val attachedToRef = event.attachedToRef
        if (attachedToRef != null) {
            sb.append(" attachedToRef=")
            sb.append(xmlAttr(attachedToRef))
            sb.append(" cancelActivity=")
            sb.append(xmlAttr(event.interrupting.toString()))
        }
        if (event.definition == EventDefinition.NONE) {
            sb.appendLine("/>")
        } else {
            sb.appendLine(">")
            val defTag = eventDefinitionTag(event.definition)
            sb.appendLine("$indent  <$defTag/>")
            sb.appendLine("$indent</$tag>")
        }
    }

    private fun eventDefinitionTag(def: EventDefinition): String =
        when (def) {
            EventDefinition.MESSAGE -> "messageEventDefinition"
            EventDefinition.TIMER -> "timerEventDefinition"
            EventDefinition.ERROR -> "errorEventDefinition"
            EventDefinition.ESCALATION -> "escalationEventDefinition"
            EventDefinition.SIGNAL -> "signalEventDefinition"
            EventDefinition.COMPENSATION -> "compensateEventDefinition"
            EventDefinition.CONDITIONAL -> "conditionalEventDefinition"
            EventDefinition.LINK -> "linkEventDefinition"
            EventDefinition.CANCEL -> "cancelEventDefinition"
            EventDefinition.TERMINATE -> "terminateEventDefinition"
            EventDefinition.MULTIPLE -> "multipleEventDefinition"
            EventDefinition.PARALLEL_MULTIPLE -> "parallelMultipleEventDefinition"
            EventDefinition.NONE -> ""
        }

    private fun appendGateway(
        gw: BpmnGateway,
        sb: StringBuilder,
        indent: String,
    ) {
        val tag =
            when (gw.gatewayType) {
                GatewayType.EXCLUSIVE -> "exclusiveGateway"
                GatewayType.INCLUSIVE -> "inclusiveGateway"
                GatewayType.PARALLEL -> "parallelGateway"
                GatewayType.EVENT_BASED -> "eventBasedGateway"
                GatewayType.COMPLEX -> "complexGateway"
            }
        sb.append("$indent<$tag id=")
        sb.append(xmlAttr(gw.id))
        gw.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        gw.defaultFlow?.let {
            sb.append(" default=")
            sb.append(xmlAttr(it))
        }
        sb.appendLine("/>")
    }

    private fun appendTask(
        task: BpmnTask,
        sb: StringBuilder,
        indent: String,
    ) {
        val tag =
            when (task.taskType) {
                TaskType.USER -> "userTask"
                TaskType.SERVICE -> "serviceTask"
                TaskType.SEND -> "sendTask"
                TaskType.RECEIVE -> "receiveTask"
                TaskType.MANUAL -> "manualTask"
                TaskType.SCRIPT -> "scriptTask"
                TaskType.BUSINESS_RULE -> "businessRuleTask"
                TaskType.NONE -> "task"
            }
        sb.append("$indent<$tag id=")
        sb.append(xmlAttr(task.id))
        task.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        sb.appendLine("/>")
    }

    private fun appendSubProcess(
        sp: BpmnSubProcess,
        sb: StringBuilder,
        indent: String,
    ) {
        sb.append("$indent<subProcess id=")
        sb.append(xmlAttr(sp.id))
        sp.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        if (sp.triggeredByEvent) sb.append(""" triggeredByEvent="true"""")
        sb.appendLine(">")
        val inner = indent + "  "
        sp.flowElementNodes.forEach { appendFlowNode(it, sb, inner) }
        sp.innerSequenceFlows.forEach { appendSequenceFlow(it, sb, inner) }
        sp.innerDataObjects.forEach { appendDataObject(it, sb, inner) }
        sb.appendLine("$indent</subProcess>")
    }

    private fun appendCallActivity(
        ca: BpmnCallActivity,
        sb: StringBuilder,
        indent: String,
    ) {
        sb.append("$indent<callActivity id=")
        sb.append(xmlAttr(ca.id))
        ca.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        ca.calledElement?.let {
            sb.append(" calledElement=")
            sb.append(xmlAttr(it))
        }
        sb.appendLine("/>")
    }

    private fun appendSequenceFlow(
        flow: SequenceFlow,
        sb: StringBuilder,
        indent: String,
    ) {
        sb.append("$indent<sequenceFlow id=")
        sb.append(xmlAttr(flow.id))
        flow.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        sb.append(" sourceRef=")
        sb.append(xmlAttr(flow.sourceRef))
        sb.append(" targetRef=")
        sb.append(xmlAttr(flow.targetRef))
        if (flow.isDefault) sb.append(""" isDefault="true"""")
        val conditionExpression = flow.conditionExpression
        if (conditionExpression != null) {
            sb.appendLine(">")
            sb.append("$indent  <conditionExpression>")
            sb.append(xmlText(conditionExpression))
            sb.appendLine("</conditionExpression>")
            sb.appendLine("$indent</sequenceFlow>")
        } else {
            sb.appendLine("/>")
        }
    }

    private fun appendDataObject(
        obj: BpmnDataObject,
        sb: StringBuilder,
        indent: String,
    ) {
        sb.append("$indent<dataObject id=")
        sb.append(xmlAttr(obj.id))
        obj.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        if (obj.collection) sb.append(""" isCollection="true"""")
        sb.appendLine("/>")
    }

    private fun appendDataStore(
        ds: BpmnDataStore,
        sb: StringBuilder,
        indent: String,
    ) {
        sb.append("$indent<dataStore id=")
        sb.append(xmlAttr(ds.id))
        ds.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        sb.appendLine("/>")
    }

    private fun appendCollaboration(
        collab: BpmnCollaboration,
        sb: StringBuilder,
    ) {
        sb.append("""  <collaboration id=""")
        sb.append(xmlAttr(collab.id))
        collab.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        sb.appendLine(">")
        collab.participants.forEach { p ->
            sb.append("""    <participant id=""")
            sb.append(xmlAttr(p.id))
            p.name?.let {
                sb.append(" name=")
                sb.append(xmlAttr(it))
            }
            p.processRef?.let {
                sb.append(" processRef=")
                sb.append(xmlAttr(it))
            }
            sb.appendLine("/>")
        }
        collab.messageFlows.forEach { mf -> appendMessageFlow(mf, sb) }
        sb.appendLine("  </collaboration>")
    }

    private fun appendMessageFlow(
        mf: MessageFlow,
        sb: StringBuilder,
    ) {
        sb.append("""    <messageFlow id=""")
        sb.append(xmlAttr(mf.id))
        mf.name?.let {
            sb.append(" name=")
            sb.append(xmlAttr(it))
        }
        sb.append(" sourceRef=")
        sb.append(xmlAttr(mf.sourceRef))
        sb.append(" targetRef=")
        sb.append(xmlAttr(mf.targetRef))
        sb.appendLine("/>")
    }

    internal fun xmlAttr(value: String): String =
        "\"" +
            value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;") + "\""

    internal fun xmlText(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
