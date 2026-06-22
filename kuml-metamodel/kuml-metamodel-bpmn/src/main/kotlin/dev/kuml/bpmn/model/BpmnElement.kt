package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlElement
import kotlinx.serialization.Serializable

/**
 * Sealed root interface for all BPMN model elements.
 *
 * Extends the open [KumlElement] core interface from kuml-core-model.
 * Exhaustive `when` expressions are possible when processing BPMN models.
 */
@Serializable
sealed interface BpmnElement : KumlElement {
    val name: String?
}

/** A BPMN flow element — can appear inside a process or sub-process flow. */
@Serializable
sealed interface BpmnFlowElement : BpmnElement

/**
 * A BPMN flow node — a flow element that has incoming and outgoing sequence flows.
 *
 * Covers tasks, gateways, events, and sub-processes.
 */
@Serializable
sealed interface BpmnFlowNode : BpmnFlowElement {
    /** IDs of incoming sequence flows. */
    val incoming: List<String>

    /** IDs of outgoing sequence flows. */
    val outgoing: List<String>
}
