package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.BpmnChoreography
import dev.kuml.bpmn.model.BpmnLoopType
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographyMessageFlow
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType

/**
 * Builder for a [BpmnChoreography].
 *
 * Instantiated via [BpmnModelBuilder.choreography]. Generates deterministic IDs
 * `"${choreographyId}_${prefix}_${counter}"` and returns the ID of each created element
 * so callers can wire sequence flows.
 *
 * Example:
 * ```kotlin
 * choreography(id = "ch1", name = "Bestellung") {
 *     val start = startEvent(name = "Bestellung eingegangen")
 *     val order = task(
 *         name = "Bestellung aufgeben",
 *         initiatingParticipant = "Käufer",
 *         participants = arrayOf("Käufer", "Händler"),
 *     ) {
 *         message(name = "Bestellanfrage", participantRef = "Käufer", isInitiating = true)
 *         message(name = "Bestätigung", participantRef = "Händler", isInitiating = false)
 *     }
 *     val end = endEvent(name = "Bestellung bestätigt")
 *     sequenceFlow(from = start, to = order)
 *     sequenceFlow(from = order, to = end)
 * }
 * ```
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@BpmnDsl
class ChoreographyBuilder(
    private val id: String,
    private val name: String?,
) {
    private val tasks = mutableListOf<ChoreographyTask>()
    private val gateways = mutableListOf<ChoreographyGateway>()
    private val events = mutableListOf<ChoreographyEvent>()
    private val sequenceFlows = mutableListOf<ChoreographySequenceFlow>()

    private var taskCounter = 0
    private var gatewayCounter = 0
    private var eventCounter = 0
    private var flowCounter = 0

    /**
     * Declares a choreography task (two-party interaction).
     *
     * @param name Interaction label.
     * @param initiatingParticipant Participant that starts the interaction.
     * @param participants The exactly two participants (pass as `arrayOf("A", "B")`).
     * @param id Optional explicit task ID; defaults to `"${choreographyId}_task_<n>"`.
     * @param block Configures messages and loop markers via [ChoreographyTaskBuilder].
     * @return The stable task ID.
     */
    fun task(
        name: String? = null,
        initiatingParticipant: String,
        participants: Array<String>,
        id: String? = null,
        block: ChoreographyTaskBuilder.() -> Unit = {},
    ): String {
        val taskId = id ?: "${this.id}_task_${++taskCounter}"
        val builder = ChoreographyTaskBuilder(taskId).apply(block)
        tasks +=
            ChoreographyTask(
                id = taskId,
                name = name,
                initiatingParticipant = initiatingParticipant,
                participants = participants.toList(),
                messageFlows = builder.messageFlows.toList(),
                isMultiInstance = builder.isMultiInstance,
                loopType = builder.loopType,
            )
        return taskId
    }

    /**
     * Declares a choreography gateway.
     *
     * @param type Routing logic (EXCLUSIVE / PARALLEL / INCLUSIVE / EVENT_BASED).
     * @param name Optional label.
     * @param id Optional explicit ID; defaults to `"${choreographyId}_gw_<n>"`.
     * @return The stable gateway ID.
     */
    fun gateway(
        type: GatewayType,
        name: String? = null,
        id: String? = null,
    ): String {
        val gwId = id ?: "${this.id}_gw_${++gatewayCounter}"
        gateways += ChoreographyGateway(id = gwId, name = name, type = type)
        return gwId
    }

    /**
     * Declares a start event.
     *
     * @param name Optional label.
     * @param id Optional explicit ID.
     * @return The stable event ID.
     */
    fun startEvent(
        name: String? = null,
        id: String? = null,
    ): String = event(EventPosition.START, name, id, "start")

    /**
     * Declares an end event.
     *
     * @param name Optional label.
     * @param id Optional explicit ID.
     * @return The stable event ID.
     */
    fun endEvent(
        name: String? = null,
        id: String? = null,
    ): String = event(EventPosition.END, name, id, "end")

    /**
     * Declares an intermediate event.
     *
     * @param name Optional label.
     * @param id Optional explicit ID.
     * @return The stable event ID.
     */
    fun intermediateEvent(
        name: String? = null,
        id: String? = null,
    ): String = event(EventPosition.INTERMEDIATE, name, id, "intermediate")

    private fun event(
        position: EventPosition,
        name: String?,
        id: String?,
        prefix: String,
    ): String {
        val evId = id ?: "${this.id}_${prefix}_${++eventCounter}"
        events += ChoreographyEvent(id = evId, name = name, position = position)
        return evId
    }

    /**
     * Declares a sequence flow between two choreography elements.
     *
     * @param from Source element ID (returned by [task], [gateway], [startEvent], etc.).
     * @param to Target element ID.
     * @param name Optional label.
     * @param condition Optional condition expression (for flows leaving a gateway).
     * @return The stable sequence-flow ID.
     */
    fun sequenceFlow(
        from: String,
        to: String,
        name: String? = null,
        condition: String? = null,
    ): String {
        val flowId = "${this.id}_flow_${++flowCounter}"
        sequenceFlows +=
            ChoreographySequenceFlow(
                id = flowId,
                name = name,
                sourceRef = from,
                targetRef = to,
                condition = condition,
            )
        return flowId
    }

    internal fun build(): BpmnChoreography =
        BpmnChoreography(
            id = id,
            name = name,
            tasks = tasks.toList(),
            gateways = gateways.toList(),
            events = events.toList(),
            sequenceFlows = sequenceFlows.toList(),
        )
}

/**
 * Builder for a single [ChoreographyTask]'s messages and loop markers.
 *
 * Instantiated by [ChoreographyBuilder.task].
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@BpmnDsl
class ChoreographyTaskBuilder(
    private val taskId: String,
) {
    internal val messageFlows = mutableListOf<ChoreographyMessageFlow>()
    private var messageCounter = 0

    /** When `true`, marks the task as multi-instance. */
    var isMultiInstance: Boolean = false

    /** Optional loop / multi-instance marker. */
    var loopType: BpmnLoopType? = null

    /**
     * Declares a message band on this task.
     *
     * @param name Optional message label.
     * @param participantRef Participant that sends this message.
     * @param isInitiating `true` for the initiating message, `false` for the return message.
     * @return The created [ChoreographyMessageFlow].
     */
    fun message(
        name: String? = null,
        participantRef: String,
        isInitiating: Boolean,
    ): ChoreographyMessageFlow {
        val mf =
            ChoreographyMessageFlow(
                id = "${taskId}_msg_${++messageCounter}",
                name = name,
                participantRef = participantRef,
                isInitiating = isInitiating,
            )
        messageFlows += mf
        return mf
    }
}

/**
 * Builder for a [dev.kuml.bpmn.model.ChoreographyDiagram].
 *
 * Instantiated via [BpmnModelBuilder.choreographyDiagram].
 *
 * Example:
 * ```kotlin
 * choreographyDiagram("Order View", choreographyId = "ch1") {
 *     include("ch1_start_1", "ch1_task_1", "ch1_end_1")
 * }
 * ```
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
@BpmnDsl
class ChoreographyDiagramBuilder(
    private val name: String,
    private val choreographyId: String,
) {
    private val elementIds = mutableListOf<String>()

    /**
     * Includes specific element IDs in this diagram view.
     *
     * When no IDs are included, the diagram shows all elements in the
     * referenced choreography.
     *
     * @param ids Element IDs to include in the view.
     */
    fun include(vararg ids: String) {
        elementIds += ids.toList()
    }

    internal fun build(): ChoreographyDiagram =
        ChoreographyDiagram(
            name = name,
            choreographyId = choreographyId,
            elementIds = elementIds.toList(),
        )
}
