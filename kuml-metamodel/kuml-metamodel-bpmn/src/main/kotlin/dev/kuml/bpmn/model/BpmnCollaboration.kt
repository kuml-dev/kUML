package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A BPMN 2.0 Collaboration — groups multiple [BpmnParticipant]s (Pools) and
 * the [MessageFlow]s that connect them across Pool boundaries.
 *
 * In BPMN 2.0, a Collaboration is a root-level container (like a Definitions
 * child) that hosts participants and their cross-pool message connections.
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable collaboration name.
 * @property participants The pools (participants) that take part in this collaboration.
 * @property messageFlows Message flows that connect elements across pool boundaries.
 * @property metadata Arbitrary additional metadata.
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
@Serializable
data class BpmnCollaboration(
    override val id: String,
    override val name: String? = null,
    val participants: List<BpmnParticipant> = emptyList(),
    val messageFlows: List<MessageFlow> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A BPMN Participant — represents a Pool in a collaboration diagram.
 *
 * When [processRef] is non-null, the pool is a *White-Box Pool* whose internal
 * flow is described by the referenced [BpmnProcess] id. When [processRef] is
 * null, the pool is a *Black-Box Pool* that hides its internals.
 *
 * @property id Stable participant identifier.
 * @property name Optional display name shown in the pool header band.
 * @property processRef ID of the [BpmnProcess] rendered inside this pool, or `null`
 *   for a black-box pool.
 * @property lanes Optional swim-lanes partitioning the pool vertically (or
 *   horizontally, for vertical pools).
 * @property horizontal `true` (default) — the pool is laid out horizontally with
 *   a vertical title band on the left.  `false` — the pool is vertical with a
 *   horizontal title band on top.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnParticipant(
    override val id: String,
    override val name: String? = null,
    val processRef: String? = null,
    val lanes: List<BpmnLane> = emptyList(),
    val horizontal: Boolean = true,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A BPMN Lane — a sub-region within a [BpmnParticipant] that groups related flow nodes.
 *
 * Lanes can be nested via [childLanes] for multi-level partition structures.
 *
 * @property id Stable lane identifier.
 * @property name Optional display name shown in the lane header band.
 * @property flowNodeRefs IDs of the flow nodes that belong to this lane.
 * @property childLanes Nested child lanes for hierarchical lane structures.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnLane(
    override val id: String,
    override val name: String? = null,
    val flowNodeRefs: List<String> = emptyList(),
    val childLanes: List<BpmnLane> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A BPMN Message Flow — a connection between two elements in *different* pools.
 *
 * Message flows represent inter-pool communication.  They differ from
 * [SequenceFlow]s, which stay within a single pool/process.
 *
 * @property id Stable message flow identifier.
 * @property name Optional label shown alongside the flow.
 * @property sourceRef ID of the source element (must be in a different pool than
 *   the target).
 * @property targetRef ID of the target element.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class MessageFlow(
    override val id: String,
    override val name: String? = null,
    val sourceRef: String,
    val targetRef: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A diagram view scoped to a single [BpmnCollaboration].
 *
 * @property name Diagram name / title.
 * @property collaborationId ID of the [BpmnCollaboration] this diagram visualises.
 * @property elementIds IDs of the elements rendered in this diagram.  An empty
 *   list means "show everything in the collaboration".
 */
@Serializable
data class CollaborationDiagram(
    override val name: String,
    val collaborationId: String,
    override val elementIds: List<String> = emptyList(),
) : BpmnDiagram
