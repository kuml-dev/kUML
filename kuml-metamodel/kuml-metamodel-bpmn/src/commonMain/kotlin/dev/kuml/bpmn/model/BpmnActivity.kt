package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/** Common interface for all BPMN activities (tasks, sub-processes, call activities). */
@Serializable
sealed interface BpmnActivity : BpmnFlowNode {
    /** Optional loop / multi-instance marker on this activity. */
    val loopCharacteristics: LoopCharacteristics?

    /** IDs of boundary events attached to this activity. */
    val boundaryEvents: List<String>
}

/**
 * An atomic BPMN task.
 *
 * @property id Stable element identifier.
 * @property name Human-readable label.
 * @property taskType Marker that determines icon and runtime semantics.
 * @property loopCharacteristics Optional loop behaviour.
 * @property boundaryEvents IDs of attached boundary events.
 * @property incoming IDs of incoming sequence flows.
 * @property outgoing IDs of outgoing sequence flows.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnTask(
    override val id: String,
    override val name: String? = null,
    val taskType: TaskType = TaskType.NONE,
    override val loopCharacteristics: LoopCharacteristics? = null,
    override val boundaryEvents: List<String> = emptyList(),
    override val incoming: List<String> = emptyList(),
    override val outgoing: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnActivity

/**
 * A BPMN sub-process — a compound activity that contains its own flow elements.
 *
 * @property id Stable element identifier.
 * @property name Human-readable label.
 * @property expanded When `true`, the sub-process is shown in an expanded (inline) form.
 * @property triggeredByEvent When `true`, this is an Event Sub-Process.
 * @property transactional When `true`, this is a Transactional Sub-Process.
 * @property flowElements IDs of the contained flow elements (for reference).
 * @property flowElementNodes Actual [BpmnFlowNode] objects of the contained flow elements.
 *   Populated only when [expanded] is `true`. Allows callers to resolve inner elements
 *   directly without going through the parent process.
 * @property innerSequenceFlows Sequence flows inside this sub-process (only when [expanded]).
 * @property innerDataObjects Data objects inside this sub-process (only when [expanded]).
 * @property innerDataAssociations Data associations inside this sub-process (only when [expanded]).
 * @property loopCharacteristics Optional loop behaviour.
 * @property boundaryEvents IDs of attached boundary events.
 * @property incoming IDs of incoming sequence flows.
 * @property outgoing IDs of outgoing sequence flows.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnSubProcess(
    override val id: String,
    override val name: String? = null,
    val expanded: Boolean = false,
    val triggeredByEvent: Boolean = false,
    val transactional: Boolean = false,
    val flowElements: List<String> = emptyList(),
    val flowElementNodes: List<BpmnFlowNode> = emptyList(),
    val innerSequenceFlows: List<SequenceFlow> = emptyList(),
    val innerDataObjects: List<BpmnDataObject> = emptyList(),
    val innerDataAssociations: List<DataAssociation> = emptyList(),
    override val loopCharacteristics: LoopCharacteristics? = null,
    override val boundaryEvents: List<String> = emptyList(),
    override val incoming: List<String> = emptyList(),
    override val outgoing: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnActivity

/**
 * A BPMN call activity — reuses a globally defined process or global task.
 *
 * @property id Stable element identifier.
 * @property name Human-readable label.
 * @property calledElement Optional qualified name / ID of the called element.
 * @property loopCharacteristics Optional loop behaviour.
 * @property boundaryEvents IDs of attached boundary events.
 * @property incoming IDs of incoming sequence flows.
 * @property outgoing IDs of outgoing sequence flows.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnCallActivity(
    override val id: String,
    override val name: String? = null,
    val calledElement: String? = null,
    override val loopCharacteristics: LoopCharacteristics? = null,
    override val boundaryEvents: List<String> = emptyList(),
    override val incoming: List<String> = emptyList(),
    override val outgoing: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnActivity
