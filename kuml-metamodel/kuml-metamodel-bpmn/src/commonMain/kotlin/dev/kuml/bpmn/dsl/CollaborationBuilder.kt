package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.MessageFlow

/**
 * Builder for a [BpmnCollaboration].
 *
 * Instantiated via [BpmnModelBuilder.collaboration].
 *
 * Example:
 * ```kotlin
 * val model = bpmnModel("Order Collaboration") {
 *     collaboration(name = "Order Collab", id = "collab1") {
 *         val buyerPool = pool("Buyer", id = "buyer") {
 *             lane("Procurement") { }
 *         }
 *         val sellerPool = pool("Seller", id = "seller") { }
 *         messageFlow(from = buyerPool, to = sellerPool, name = "Order Request")
 *     }
 *     collaborationDiagram("Collab View", collaborationId = "collab1")
 * }
 * ```
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
@BpmnDsl
class CollaborationBuilder(
    private val id: String,
    private val name: String?,
) {
    private val participants = mutableListOf<BpmnParticipant>()
    private val messageFlows = mutableListOf<MessageFlow>()
    private var mfCounter = 0

    /**
     * Declares a White-Box Pool (participant with visible internal process).
     *
     * @param name Optional display name for the pool header band.
     * @param id Optional explicit pool ID; defaults to `"pool_<n>"`.
     * @param horizontal Pool orientation — `true` (default) for horizontal pools with a
     *   vertical header on the left, `false` for vertical pools with a horizontal header
     *   on top.
     * @param block Block to configure lanes and optional process reference.
     * @return The stable pool ID for use in [messageFlow] calls.
     */
    fun pool(
        name: String? = null,
        id: String? = null,
        horizontal: Boolean = true,
        block: PoolBuilder.() -> Unit = {},
    ): String {
        val pid = id ?: "pool_${participants.size + 1}"
        val builder = PoolBuilder(pid, name, horizontal).apply(block)
        participants += builder.build()
        return pid
    }

    /**
     * Declares a Black-Box Pool — a participant whose internal process is not shown.
     *
     * @param name Optional display name for the pool header band.
     * @param id Optional explicit pool ID; defaults to `"pool_<n>"`.
     * @return The stable pool ID.
     */
    fun blackBoxPool(
        name: String? = null,
        id: String? = null,
    ): String {
        val pid = id ?: "pool_${participants.size + 1}"
        participants += BpmnParticipant(id = pid, name = name, processRef = null)
        return pid
    }

    /**
     * Declares a Message Flow connecting elements in two different pools.
     *
     * @param from Source element ID (in one pool).
     * @param to Target element ID (in another pool).
     * @param name Optional label shown alongside the message flow arrow.
     * @return The stable message flow ID.
     */
    fun messageFlow(
        from: String,
        to: String,
        name: String? = null,
    ): String {
        val mfId = "${this.id}_mf_${++mfCounter}"
        messageFlows += MessageFlow(id = mfId, name = name, sourceRef = from, targetRef = to)
        return mfId
    }

    internal fun build(): BpmnCollaboration =
        BpmnCollaboration(
            id = id,
            name = name,
            participants = participants.toList(),
            messageFlows = messageFlows.toList(),
        )
}

/**
 * Builder for a [BpmnParticipant] (Pool).
 *
 * Instantiated via [CollaborationBuilder.pool].
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
@BpmnDsl
class PoolBuilder(
    private val id: String,
    private val name: String?,
    private val horizontal: Boolean,
) {
    private val lanes = mutableListOf<BpmnLane>()
    private var laneCounter = 0

    /** References an existing process by ID as the internal process for this pool. */
    var processRef: String? = null

    /**
     * Declares a lane within this pool.
     *
     * @param name Optional display name shown in the lane header band.
     * @param block Block to configure lane contents and optional child lanes.
     * @return The stable lane ID.
     */
    fun lane(
        name: String? = null,
        block: LaneBuilder.() -> Unit = {},
    ): String {
        val laneId = "${id}_lane_${++laneCounter}"
        val builder = LaneBuilder(laneId, name).apply(block)
        lanes += builder.build()
        return laneId
    }

    /** Sets the process reference for this pool. */
    fun process(processId: String) {
        processRef = processId
    }

    internal fun build(): BpmnParticipant =
        BpmnParticipant(
            id = id,
            name = name,
            processRef = processRef,
            lanes = lanes.toList(),
            horizontal = horizontal,
        )
}

/**
 * Builder for a [BpmnLane].
 *
 * Instantiated via [PoolBuilder.lane] or nested [LaneBuilder.lane].
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
@BpmnDsl
class LaneBuilder(
    private val id: String,
    private val name: String?,
) {
    private val flowNodeRefs = mutableListOf<String>()
    private val childLanes = mutableListOf<BpmnLane>()
    private var childCounter = 0

    /**
     * Associates flow node IDs with this lane.
     *
     * @param nodeIds One or more flow node IDs that belong to this lane.
     */
    fun contains(vararg nodeIds: String) {
        flowNodeRefs += nodeIds.toList()
    }

    /**
     * Declares a nested child lane.
     *
     * @param name Optional display name for the child lane.
     * @param block Block to configure the child lane.
     * @return The stable child lane ID.
     */
    fun lane(
        name: String? = null,
        block: LaneBuilder.() -> Unit = {},
    ): String {
        val childId = "${id}_child_${++childCounter}"
        childLanes += LaneBuilder(childId, name).apply(block).build()
        return childId
    }

    internal fun build(): BpmnLane =
        BpmnLane(
            id = id,
            name = name,
            flowNodeRefs = flowNodeRefs.toList(),
            childLanes = childLanes.toList(),
        )
}
