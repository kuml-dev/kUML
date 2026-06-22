package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/** Where in a process flow the event appears. */
@Serializable
enum class EventPosition {
    START,
    INTERMEDIATE,
    END,
}

/** Whether the event waits for a trigger (catching) or emits one (throwing). */
@Serializable
enum class EventBehaviour {
    CATCHING,
    THROWING,
}

/** The trigger type associated with a BPMN event. */
@Serializable
enum class EventDefinition {
    NONE,
    MESSAGE,
    TIMER,
    ERROR,
    ESCALATION,
    SIGNAL,
    COMPENSATION,
    CONDITIONAL,
    LINK,
    CANCEL,
    MULTIPLE,
    PARALLEL_MULTIPLE,
    TERMINATE,
}

/**
 * A BPMN event — a start, intermediate, or end event with an optional trigger definition.
 *
 * Constraints enforced at construction time:
 * - START events must be CATCHING.
 * - END events must be THROWING.
 * - TERMINATE definition is only valid at END position.
 * - LINK definition is only valid at INTERMEDIATE position.
 * - CANCEL definition is only valid at END position.
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable label.
 * @property position Where in the flow the event appears.
 * @property definition The trigger type.
 * @property behaviour Whether the event catches or throws.
 * @property interrupting For boundary / start events inside event sub-processes: whether it interrupts.
 * @property attachedToRef ID of the activity this event is attached to (boundary events only).
 * @property incoming IDs of incoming sequence flows.
 * @property outgoing IDs of outgoing sequence flows.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnEvent(
    override val id: String,
    override val name: String? = null,
    val position: EventPosition,
    val definition: EventDefinition = EventDefinition.NONE,
    val behaviour: EventBehaviour = EventBehaviour.CATCHING,
    val interrupting: Boolean = true,
    val attachedToRef: String? = null,
    override val incoming: List<String> = emptyList(),
    override val outgoing: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnFlowNode {
    init {
        require(position != EventPosition.START || behaviour == EventBehaviour.CATCHING) {
            "START events must be CATCHING (got behaviour=$behaviour)"
        }
        require(position != EventPosition.END || behaviour == EventBehaviour.THROWING) {
            "END events must be THROWING (got behaviour=$behaviour)"
        }
        require(definition != EventDefinition.TERMINATE || position == EventPosition.END) {
            "TERMINATE definition is only valid for END events (got position=$position)"
        }
        require(definition != EventDefinition.LINK || position == EventPosition.INTERMEDIATE) {
            "LINK definition is only valid for INTERMEDIATE events (got position=$position)"
        }
        require(definition != EventDefinition.CANCEL || position == EventPosition.END) {
            "CANCEL definition is only valid for END events (got position=$position)"
        }
    }
}
