package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Loop / multi-instance marker for a [ChoreographyTask].
 *
 * BPMN 2.0 choreography activities carry a simplified loop marker (None / Standard /
 * MultiInstance parallel / MultiInstance sequential), rendered as the familiar loop or
 * three-bar / three-line marker at the bottom of the task band.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
enum class BpmnLoopType {
    NONE,
    STANDARD,
    MULTI_INSTANCE_PARALLEL,
    MULTI_INSTANCE_SEQUENTIAL,
}

/**
 * A BPMN Choreography Message Flow — one message band attached to a [ChoreographyTask].
 *
 * A choreography task has one (one-way) or two (request/response) messages. The
 * [isInitiating] message is rendered as an un-filled (white) envelope band, the
 * non-initiating (return) message as a filled (grey) band.
 *
 * @property id Stable message flow identifier.
 * @property name Optional message label.
 * @property participantRef ID/name of the participant that sends this message.
 * @property isInitiating `true` for the initiating message, `false` for the return message.
 * @property metadata Arbitrary additional metadata.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
data class ChoreographyMessageFlow(
    override val id: String,
    override val name: String? = null,
    val participantRef: String,
    val isInitiating: Boolean,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A BPMN Choreography Task — an interaction between exactly two participants.
 *
 * Models a single message exchange (or request/response pair) in a choreography. The
 * [initiatingParticipant] sends the first message; the task band shows both participant
 * names (initiator on top, recipient on bottom) with the message envelope(s) attached.
 *
 * Constraints enforced at construction time:
 * - exactly two participants;
 * - the initiating participant must be one of the two;
 * - at most one initiating message.
 *
 * @property id Stable element identifier.
 * @property name Human-readable interaction label.
 * @property initiatingParticipant Participant that starts the interaction (must be in [participants]).
 * @property participants The two participants of this interaction.
 * @property messageFlows One (one-way) or two (request/response) message bands.
 * @property isMultiInstance Convenience flag mirrored by [loopType].
 * @property loopType Optional loop / multi-instance marker.
 * @property metadata Arbitrary additional metadata.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
data class ChoreographyTask(
    override val id: String,
    override val name: String? = null,
    val initiatingParticipant: String,
    val participants: List<String> = emptyList(),
    val messageFlows: List<ChoreographyMessageFlow> = emptyList(),
    val isMultiInstance: Boolean = false,
    val loopType: BpmnLoopType? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement {
    init {
        require(participants.size == 2) {
            "A ChoreographyTask must have exactly two participants (got ${participants.size}: $participants)"
        }
        require(initiatingParticipant in participants) {
            "initiatingParticipant '$initiatingParticipant' must be one of the participants $participants"
        }
        require(messageFlows.count { it.isInitiating } <= 1) {
            "A ChoreographyTask must have at most one initiating message"
        }
        require(
            if (isMultiInstance) {
                loopType == BpmnLoopType.MULTI_INSTANCE_PARALLEL || loopType == BpmnLoopType.MULTI_INSTANCE_SEQUENTIAL
            } else {
                true
            },
        ) {
            "isMultiInstance=true requires loopType to be MULTI_INSTANCE_PARALLEL or MULTI_INSTANCE_SEQUENTIAL " +
                "(got loopType=$loopType)"
        }
    }
}

/**
 * A BPMN Choreography Gateway — branches/merges the choreography flow.
 *
 * Reuses the process-level [GatewayType] (EXCLUSIVE / PARALLEL / INCLUSIVE / EVENT_BASED).
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable label.
 * @property type Routing logic.
 * @property metadata Arbitrary additional metadata.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
data class ChoreographyGateway(
    override val id: String,
    override val name: String? = null,
    val type: GatewayType,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A BPMN Choreography Event — start, intermediate, or end event in a choreography flow.
 *
 * Choreography diagrams use plain (untyped) start/intermediate/end events to anchor the
 * flow. [position] reuses the process-level [EventPosition].
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable label.
 * @property position START / INTERMEDIATE / END.
 * @property metadata Arbitrary additional metadata.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
data class ChoreographyEvent(
    override val id: String,
    override val name: String? = null,
    val position: EventPosition,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A BPMN Choreography Sequence Flow — connects two choreography elements.
 *
 * @property id Stable element identifier.
 * @property name Optional label.
 * @property sourceRef ID of the source choreography element.
 * @property targetRef ID of the target choreography element.
 * @property condition Optional condition expression (for flows leaving a gateway).
 * @property metadata Arbitrary additional metadata.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
data class ChoreographySequenceFlow(
    override val id: String,
    override val name: String? = null,
    val sourceRef: String,
    val targetRef: String,
    val condition: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A BPMN Choreography — container for a choreography flow (analogous to [BpmnProcess]).
 *
 * Holds the choreography tasks, gateways, events, and the sequence flows that connect them.
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable name.
 * @property tasks Choreography tasks (two-party interactions).
 * @property gateways Choreography gateways.
 * @property events Choreography events (start / intermediate / end).
 * @property sequenceFlows Choreography sequence flows.
 * @property metadata Arbitrary additional metadata.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
data class BpmnChoreography(
    override val id: String,
    override val name: String? = null,
    val tasks: List<ChoreographyTask> = emptyList(),
    val gateways: List<ChoreographyGateway> = emptyList(),
    val events: List<ChoreographyEvent> = emptyList(),
    val sequenceFlows: List<ChoreographySequenceFlow> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement {
    /**
     * Looks up any element by ID within this choreography, including message flows
     * nested inside tasks.
     */
    fun elementById(id: String): BpmnElement? =
        tasks.firstOrNull { it.id == id }
            ?: gateways.firstOrNull { it.id == id }
            ?: events.firstOrNull { it.id == id }
            ?: sequenceFlows.firstOrNull { it.id == id }
            ?: tasks.firstNotNullOfOrNull { task -> task.messageFlows.firstOrNull { it.id == id } }
}

/**
 * A diagram view scoped to a single [BpmnChoreography].
 *
 * @property name Diagram name / title.
 * @property choreographyId ID of the [BpmnChoreography] this diagram visualises.
 * @property elementIds IDs of elements to render; empty means "show everything".
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@Serializable
data class ChoreographyDiagram(
    override val name: String,
    val choreographyId: String,
    override val elementIds: List<String> = emptyList(),
) : BpmnDiagram
