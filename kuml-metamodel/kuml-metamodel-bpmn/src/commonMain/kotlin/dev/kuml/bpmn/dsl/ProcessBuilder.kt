package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.DataAssociation
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.TaskType
import dev.kuml.uml.UmlConstraint

/**
 * Builder for a [BpmnProcess].
 *
 * Collects flow nodes (tasks, gateways, events, sub-processes), sequence flows,
 * and data elements as the user populates the process block. Auto-generates
 * stable, deterministic IDs for every element based on the process id and a
 * per-type counter — no manual id management required.
 *
 * Infix helper: use `val start = startEvent(); start flowsTo task1 flowsTo end`
 * for a readable chain syntax that automatically creates sequence flows.
 */
@BpmnDsl
class ProcessBuilder(
    val id: String,
    val name: String?,
) {
    private val flowNodes: MutableList<BpmnFlowNode> = mutableListOf()
    private val sequenceFlows: MutableList<SequenceFlow> = mutableListOf()
    private val dataObjects: MutableList<BpmnDataObject> = mutableListOf()
    private val dataAssociations: MutableList<DataAssociation> = mutableListOf()
    private val constraints: MutableList<UmlConstraint> = mutableListOf()

    private var nodeCounter = 0
    private var flowCounter = 0
    private var dataCounter = 0
    private var constraintCounter = 0

    private fun nextNodeId(prefix: String): String = "${id}_${prefix}_${++nodeCounter}"

    private fun nextFlowId(): String = "${id}_flow_${++flowCounter}"

    private fun nextDataId(prefix: String): String = "${id}_${prefix}_${++dataCounter}"

    // ── Events ──────────────────────────────────────────────────────────────

    /**
     * Declare a start event.
     *
     * @param name Optional human-readable label.
     * @param definition The trigger type (defaults to [EventDefinition.NONE]).
     * @return The generated element id — use it as source for [sequenceFlow] / [flowsTo].
     */
    fun startEvent(
        name: String? = null,
        definition: EventDefinition = EventDefinition.NONE,
    ): String {
        val nodeId = nextNodeId("start")
        flowNodes +=
            BpmnEvent(
                id = nodeId,
                name = name,
                position = EventPosition.START,
                definition = definition,
                behaviour = EventBehaviour.CATCHING,
            )
        return nodeId
    }

    /**
     * Declare an end event.
     *
     * @param name Optional human-readable label.
     * @param definition The trigger type (defaults to [EventDefinition.NONE]).
     * @return The generated element id.
     */
    fun endEvent(
        name: String? = null,
        definition: EventDefinition = EventDefinition.NONE,
    ): String {
        val nodeId = nextNodeId("end")
        flowNodes +=
            BpmnEvent(
                id = nodeId,
                name = name,
                position = EventPosition.END,
                definition = definition,
                behaviour = EventBehaviour.THROWING,
            )
        return nodeId
    }

    /**
     * Declare an intermediate event.
     *
     * @param name Optional human-readable label.
     * @param definition The trigger type (defaults to [EventDefinition.NONE]).
     * @param throwing When `true`, the event throws the trigger; when `false` it catches it.
     * @return The generated element id.
     */
    fun intermediateEvent(
        name: String? = null,
        definition: EventDefinition = EventDefinition.NONE,
        throwing: Boolean = false,
    ): String {
        val nodeId = nextNodeId("intermediate")
        flowNodes +=
            BpmnEvent(
                id = nodeId,
                name = name,
                position = EventPosition.INTERMEDIATE,
                definition = definition,
                behaviour = if (throwing) EventBehaviour.THROWING else EventBehaviour.CATCHING,
            )
        return nodeId
    }

    /**
     * Declare a boundary event attached to another element.
     *
     * @param attachedTo The id of the activity this event is attached to.
     * @param name Optional human-readable label.
     * @param definition The trigger type (defaults to [EventDefinition.NONE]).
     * @param interrupting When `true`, the event interrupts the attached activity.
     * @return The generated element id.
     */
    fun boundaryEvent(
        attachedTo: String,
        name: String? = null,
        definition: EventDefinition = EventDefinition.NONE,
        interrupting: Boolean = true,
    ): String {
        val nodeId = nextNodeId("boundary")
        flowNodes +=
            BpmnEvent(
                id = nodeId,
                name = name,
                position = EventPosition.INTERMEDIATE,
                definition = definition,
                behaviour = EventBehaviour.CATCHING,
                interrupting = interrupting,
                attachedToRef = attachedTo,
            )
        return nodeId
    }

    // ── Tasks ────────────────────────────────────────────────────────────────

    /**
     * Declare a task.
     *
     * @param name Optional human-readable label.
     * @param type The task type marker (defaults to [TaskType.NONE]).
     * @param block Optional block to configure loop markers and boundary events via [TaskBuilder].
     * @return The generated element id.
     */
    fun task(
        name: String? = null,
        type: TaskType = TaskType.NONE,
        block: TaskBuilder.() -> Unit = {},
    ): String {
        val nodeId = nextNodeId("task")
        val builder = TaskBuilder().apply(block)
        flowNodes +=
            BpmnTask(
                id = nodeId,
                name = name,
                taskType = type,
                loopCharacteristics = builder.loopCharacteristics,
                boundaryEvents = builder.boundaryEventIds.toList(),
            )
        return nodeId
    }

    /**
     * Declare a sub-process.
     *
     * @param name Optional human-readable label.
     * @param expanded When `true`, the sub-process is shown in expanded (inline) form.
     * @param triggeredByEvent When `true`, this is an Event Sub-Process.
     * @param transactional When `true`, this is a Transactional Sub-Process.
     * @param block Optional block that populates the inner process via a nested [ProcessBuilder].
     * @return The generated element id.
     */
    fun subProcess(
        name: String? = null,
        expanded: Boolean = false,
        triggeredByEvent: Boolean = false,
        transactional: Boolean = false,
        block: ProcessBuilder.() -> Unit = {},
    ): String {
        val nodeId = nextNodeId("sub")
        val inner = ProcessBuilder(id = nodeId, name = name).apply(block)
        flowNodes +=
            BpmnSubProcess(
                id = nodeId,
                name = name,
                expanded = expanded,
                triggeredByEvent = triggeredByEvent,
                transactional = transactional,
                flowElements = if (expanded) inner.flowNodes.map { it.id } else emptyList(),
                flowElementNodes = if (expanded) inner.flowNodes.toList() else emptyList(),
                innerSequenceFlows = if (expanded) inner.sequenceFlows.toList() else emptyList(),
                innerDataObjects = if (expanded) inner.dataObjects.toList() else emptyList(),
                innerDataAssociations = if (expanded) inner.dataAssociations.toList() else emptyList(),
            )
        return nodeId
    }

    /**
     * Declare a call activity — reuses a globally defined process or global task.
     *
     * @param name Optional human-readable label.
     * @param calledElement Optional qualified name / id of the called element.
     * @return The generated element id.
     */
    fun callActivity(
        name: String? = null,
        calledElement: String? = null,
    ): String {
        val nodeId = nextNodeId("call")
        flowNodes += BpmnCallActivity(id = nodeId, name = name, calledElement = calledElement)
        return nodeId
    }

    // ── Gateways ─────────────────────────────────────────────────────────────

    /**
     * Declare a gateway.
     *
     * @param type The routing logic of the gateway.
     * @param name Optional human-readable label.
     * @param default Optional id of the default (else) sequence flow.
     * @return The generated element id.
     */
    fun gateway(
        type: GatewayType,
        name: String? = null,
        default: String? = null,
    ): String {
        val nodeId = nextNodeId("gw")
        flowNodes +=
            BpmnGateway(
                id = nodeId,
                name = name,
                gatewayType = type,
                defaultFlow = default,
            )
        return nodeId
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    /**
     * Declare a data object.
     *
     * @param name Optional human-readable label.
     * @param collection When `true`, this data object represents a collection.
     * @return The generated element id.
     */
    fun dataObject(
        name: String? = null,
        collection: Boolean = false,
    ): String {
        val dataId = nextDataId("data")
        dataObjects += BpmnDataObject(id = dataId, name = name, collection = collection)
        return dataId
    }

    /**
     * Declare a data association between a source element and a target element.
     *
     * @param from ID of the source element.
     * @param to ID of the target element.
     * @return The generated association id.
     */
    fun dataAssociation(
        from: String,
        to: String,
    ): String {
        val assocId = nextDataId("assoc")
        dataAssociations += DataAssociation(id = assocId, sourceRef = from, targetRef = to)
        return assocId
    }

    // ── Constraints (V3.2.23) ───────────────────────────────────────────────────

    /**
     * Declare an OCL invariant on this process.
     *
     * Evaluated by `kuml-core-ocl`'s `OclValidator` BPMN branch with `self` bound
     * to the enclosing [BpmnProcess]. Property navigation (`self.flowNodes`,
     * `self.sequenceFlows`, …) is resolved by the BPMN property accessor —
     * analogous to how UML classifier constraints navigate `self.attributes`.
     *
     * @param name Constraint name, shown in violation messages.
     * @param body OCL expression body, e.g. `"self.flowNodes->notEmpty()"`.
     * @return The generated constraint id.
     */
    fun constraint(
        name: String,
        body: String,
    ): String {
        val constraintId = "${id}_inv_${++constraintCounter}"
        constraints += UmlConstraint(id = constraintId, name = name, body = body)
        return constraintId
    }

    // ── Flows ─────────────────────────────────────────────────────────────────

    /**
     * Declare a sequence flow between two flow nodes.
     *
     * @param from ID of the source flow node.
     * @param to ID of the target flow node.
     * @param condition Optional condition expression that must evaluate to `true`.
     * @param name Optional human-readable label for this flow.
     * @param default When `true`, this is the default flow of a gateway.
     */
    fun sequenceFlow(
        from: String,
        to: String,
        condition: String? = null,
        name: String? = null,
        default: Boolean = false,
    ) {
        sequenceFlows +=
            SequenceFlow(
                id = nextFlowId(),
                sourceRef = from,
                targetRef = to,
                conditionExpression = condition,
                name = name,
                isDefault = default,
            )
    }

    /**
     * Infix helper — creates a sequence flow from receiver to [target] and returns [target].
     *
     * Allows chaining: `val start = startEvent(); start flowsTo task1 flowsTo end`
     *
     * @receiver The source element id.
     * @param target The target element id.
     * @return [target], enabling left-associative chaining.
     */
    infix fun String.flowsTo(target: String): String {
        sequenceFlow(this, target)
        return target
    }

    internal fun build(): BpmnProcess =
        BpmnProcess(
            id = id,
            name = name,
            flowNodes = flowNodes.toList(),
            sequenceFlows = sequenceFlows.toList(),
            dataObjects = dataObjects.toList(),
            dataAssociations = dataAssociations.toList(),
            constraints = constraints.toList(),
        )
}
