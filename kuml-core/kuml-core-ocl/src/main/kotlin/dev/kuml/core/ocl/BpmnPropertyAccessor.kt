package dev.kuml.core.ocl

import dev.kuml.bpmn.model.BpmnActivity
import dev.kuml.bpmn.model.BpmnElement
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.SequenceFlow

/**
 * Resolves `self.prop` navigations for [BpmnElement] receivers (V3.2.23).
 *
 * Analogous to [UmlPropertyAccessor], but for the BPMN metamodel — no
 * association-end/attribute reflection is needed here since [BpmnProcess]
 * and its flow nodes are plain data classes with a small, fixed set of
 * navigable properties.
 *
 * Returns [NOT_FOUND] (rather than throwing) when [self] is not a
 * [BpmnElement] or [prop] has no BPMN-specific mapping, so [PropertyAccessor]
 * can fall through to the next accessor in its dispatch chain.
 */
internal object BpmnPropertyAccessor {
    internal fun get(
        self: Any,
        prop: String,
    ): Any =
        when {
            self is BpmnProcess && prop == "name" -> self.name ?: NOT_FOUND
            self is BpmnProcess && prop == "flowNodes" -> self.flowNodes
            self is BpmnProcess && prop == "sequenceFlows" -> self.sequenceFlows
            self is BpmnProcess && prop == "dataObjects" -> self.dataObjects
            self is BpmnProcess && prop == "dataAssociations" -> self.dataAssociations
            self is BpmnFlowNode && prop == "name" -> self.name ?: NOT_FOUND
            self is BpmnFlowNode && prop == "id" -> self.id
            self is BpmnFlowNode && prop == "incoming" -> self.incoming
            self is BpmnFlowNode && prop == "outgoing" -> self.outgoing
            self is BpmnActivity && prop == "boundaryEvents" -> self.boundaryEvents
            self is BpmnEvent && prop == "position" -> self.position.name
            self is BpmnEvent && prop == "attachedToRef" -> self.attachedToRef ?: NOT_FOUND
            self is BpmnGateway && prop == "gatewayType" -> self.gatewayType.name
            self is BpmnGateway && prop == "defaultFlow" -> self.defaultFlow ?: NOT_FOUND
            self is SequenceFlow && prop == "sourceRef" -> self.sourceRef
            self is SequenceFlow && prop == "targetRef" -> self.targetRef
            self is SequenceFlow && prop == "condition" -> self.conditionExpression ?: NOT_FOUND
            else -> NOT_FOUND
        }

    /** Sentinel distinguishing "no BPMN-specific mapping" from a legitimate `null` navigation result. */
    internal val NOT_FOUND = Any()
}
