package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Top-level BPMN model — contains one or more processes and associated diagrams.
 *
 * In BPMN 2.0, [BpmnDataStore] is a root-level element (RootElement) belonging to the
 * Definitions container, not to a particular process. Accordingly, [dataStores] lives here
 * on [BpmnModel] rather than on [BpmnProcess].
 *
 * @property name Human-readable model name.
 * @property processes All processes defined in this model.
 * @property dataStores All data stores defined at model (root) level.
 * @property diagrams All diagram views for the model.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnModel(
    val name: String,
    val processes: List<BpmnProcess> = emptyList(),
    val dataStores: List<BpmnDataStore> = emptyList(),
    val collaborations: List<BpmnCollaboration> = emptyList(),
    val diagrams: List<BpmnDiagram> = emptyList(),
    val metadata: Map<String, KumlMetaValue> = emptyMap(),
) {
    /**
     * Looks up any element by ID across all processes, root-level data stores,
     * and collaborations (including their participants, lanes, and message flows)
     * in this model.
     *
     * @param id The element ID to look up.
     * @return The matching [BpmnElement], or `null` if not found.
     */
    fun elementById(id: String): BpmnElement? =
        dataStores.firstOrNull { it.id == id }
            ?: processes.firstNotNullOfOrNull { it.elementById(id) }
            ?: collaborations.firstOrNull { it.id == id }
            ?: collaborations.firstNotNullOfOrNull { collab -> collab.elementById(id) }

    /** Looks up an element within a specific collaboration by ID. */
    private fun BpmnCollaboration.elementById(id: String): BpmnElement? =
        participants.firstOrNull { it.id == id }
            ?: messageFlows.firstOrNull { it.id == id }
            ?: participants.firstNotNullOfOrNull { participant -> participant.laneById(id) }

    private fun BpmnParticipant.laneById(id: String): BpmnElement? =
        lanes.firstOrNull { it.id == id }
            ?: lanes.firstNotNullOfOrNull { lane -> lane.laneById(id) }

    private fun BpmnLane.laneById(id: String): BpmnElement? =
        childLanes.firstOrNull { it.id == id }
            ?: childLanes.firstNotNullOfOrNull { child -> child.laneById(id) }
}

/** A named diagram view that references elements from one or more processes. */
@Serializable
sealed interface BpmnDiagram {
    val name: String
    val elementIds: List<String>
}

/**
 * A diagram view scoped to a single process.
 *
 * @property name Diagram name / title.
 * @property processId ID of the process this diagram visualises.
 * @property elementIds IDs of the elements rendered in this diagram.
 */
@Serializable
data class ProcessDiagram(
    override val name: String,
    val processId: String,
    override val elementIds: List<String> = emptyList(),
) : BpmnDiagram
