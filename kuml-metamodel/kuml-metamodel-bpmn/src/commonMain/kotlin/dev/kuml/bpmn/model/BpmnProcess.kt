package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlConstraint
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
 * @property constraints Process-level OCL invariants (V3.2.23). Evaluated with `self`
 *   bound to this [BpmnProcess] by `kuml-core-ocl`'s `OclValidator` BPMN branch —
 *   reuses [UmlConstraint] (the same shape UML classifiers use) rather than a
 *   parallel BPMN-specific constraint type, so the OCL lexer/parser/evaluator need
 *   no BPMN-specific code path.
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
    val constraints: List<UmlConstraint> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement {
    /**
     * Looks up any element by ID within this process.
     *
     * Searches flow nodes, sequence flows, data objects, and data associations.
     * For expanded sub-processes, also recurses into their inner flow elements,
     * sequence flows, data objects, and data associations.
     *
     * @param id The element ID to look up.
     * @return The matching [BpmnElement], or `null` if not found.
     */
    fun elementById(id: String): BpmnElement? =
        flowNodes.firstOrNull { it.id == id }
            ?: sequenceFlows.firstOrNull { it.id == id }
            ?: dataObjects.firstOrNull { it.id == id }
            ?: dataAssociations.firstOrNull { it.id == id }
            ?: flowNodes.filterIsInstance<BpmnSubProcess>().firstNotNullOfOrNull { sp ->
                sp.flowElementNodes.firstOrNull { it.id == id }
                    ?: sp.innerSequenceFlows.firstOrNull { it.id == id }
                    ?: sp.innerDataObjects.firstOrNull { it.id == id }
                    ?: sp.innerDataAssociations.firstOrNull { it.id == id }
            }

    /**
     * Returns all renderable elements of this process as a flat [KumlElement] list:
     * flow nodes, sequence flows, and data objects — plus, for expanded
     * sub-processes, their inner flow nodes, sequence flows, and data objects
     * (recursively).
     *
     * The BPMN process SVG renderer builds its element index from this list and
     * looks up every laid-out node by id. Without the expanded sub-process
     * children, those inner nodes would be laid out but silently dropped at
     * render time.
     */
    fun renderableElements(): List<KumlElement> {
        val acc = mutableListOf<KumlElement>()
        acc += flowNodes
        acc += sequenceFlows
        acc += dataObjects
        flowNodes
            .filterIsInstance<BpmnSubProcess>()
            .filter { it.expanded }
            .forEach { collectExpandedSubProcess(it, acc) }
        return acc
    }

    private fun collectExpandedSubProcess(
        sp: BpmnSubProcess,
        acc: MutableList<KumlElement>,
    ) {
        acc += sp.flowElementNodes
        acc += sp.innerSequenceFlows
        acc += sp.innerDataObjects
        sp.flowElementNodes
            .filterIsInstance<BpmnSubProcess>()
            .filter { it.expanded }
            .forEach { collectExpandedSubProcess(it, acc) }
    }
}
