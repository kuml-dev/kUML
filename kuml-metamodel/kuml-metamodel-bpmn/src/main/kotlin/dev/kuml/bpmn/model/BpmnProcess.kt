package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A BPMN process — the top-level container of a process flow.
 *
 * @property id Stable element identifier.
 * @property name Human-readable process name.
 * @property flowNodes All flow nodes (tasks, gateways, events, sub-processes) in this process.
 * @property sequenceFlows All sequence flows connecting the flow nodes.
 * @property dataObjects Data objects referenced within this process.
 * @property dataAssociations Data associations between data objects and activities.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnProcess(
    override val id: String,
    override val name: String? = null,
    val flowNodes: List<BpmnFlowNode> = emptyList(),
    val sequenceFlows: List<SequenceFlow> = emptyList(),
    val dataObjects: List<BpmnDataObject> = emptyList(),
    val dataAssociations: List<DataAssociation> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement {
    /**
     * Looks up any element by ID within this process.
     *
     * Searches flow nodes, sequence flows, data objects, and data associations.
     *
     * @param id The element ID to look up.
     * @return The matching [BpmnElement], or `null` if not found.
     */
    fun elementById(id: String): BpmnElement? =
        flowNodes.firstOrNull { it.id == id }
            ?: sequenceFlows.firstOrNull { it.id == id }
            ?: dataObjects.firstOrNull { it.id == id }
            ?: dataAssociations.firstOrNull { it.id == id }
}
