package dev.kuml.bpmn.constraint

import dev.kuml.bpmn.model.BpmnChoreography
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType

/**
 * A single constraint violation found by [BpmnConstraintChecker].
 *
 * @property elementId ID of the element that caused the violation, or null for model-level issues.
 * @property message Human-readable description of the violation.
 * @property severity Whether this is an error (blocks rendering) or a warning (advisory only).
 *
 * V3.1.6
 */
public data class ConstraintViolation(
    val elementId: String?,
    val message: String,
    val severity: ViolationSeverity,
)

/** Severity of a [ConstraintViolation]. */
public enum class ViolationSeverity {
    /** Structural error — the model is incomplete or internally inconsistent. */
    ERROR,

    /** Advisory warning — the model is valid but may not follow BPMN best practices. */
    WARNING,
}

/**
 * Checks a [BpmnModel] for structural constraint violations.
 *
 * Rules checked:
 * 1. Every process should have at least one StartEvent — WARNING.
 * 2. Every process should have at least one EndEvent — WARNING.
 * 3. Every SequenceFlow's sourceRef must exist in the process — ERROR.
 * 4. Every SequenceFlow's targetRef must exist in the process — ERROR.
 * 5. XOR/OR gateway with multiple outgoing flows and no defaultFlow — WARNING.
 * 6. Boundary event's attachedToRef must exist in the process — ERROR.
 * 7. MessageFlow with identical source and target — ERROR.
 * 8. Participant.processRef must reference an existing process — ERROR.
 * 9. Choreography SequenceFlow source/target must reference an existing element — ERROR.
 * 10. Choreography should have at least one StartEvent without incoming flows — WARNING.
 * 11. Choreography EndEvents should have no outgoing flows — ERROR.
 * 12. Choreography elements should be reachable from a StartEvent — WARNING.
 * 13. Condition set on a flow leaving a non-EXCLUSIVE/INCLUSIVE gateway — WARNING.
 * 14. Initiating ChoreographyMessageFlow.participantRef must match task.initiatingParticipant — ERROR.
 * 15. Choreography task participant-band continuity between connected tasks — WARNING.
 *
 * V3.1.6, V3.2.2 (choreography rules)
 */
public class BpmnConstraintChecker {
    /**
     * Checks all processes, collaborations, and choreographies in [model] and returns the
     * list of violations found. An empty list means the model passes all checks.
     */
    public fun check(model: BpmnModel): List<ConstraintViolation> =
        buildList {
            model.processes.forEach { process -> checkProcess(process, this) }
            model.collaborations.forEach { collab -> checkCollaboration(collab, model, this) }
            model.choreographies.forEach { choreography -> checkChoreography(choreography, this) }
        }

    private fun checkProcess(
        process: BpmnProcess,
        violations: MutableList<ConstraintViolation>,
    ) {
        val nodeIds = process.flowNodes.map { it.id }.toSet()

        // 1. Every process should have at least one StartEvent (WARNING)
        val hasStart = process.flowNodes.any { it is BpmnEvent && it.position == EventPosition.START }
        if (!hasStart) {
            violations +=
                ConstraintViolation(
                    elementId = process.id,
                    message = "Process '${process.name ?: process.id}' has no StartEvent",
                    severity = ViolationSeverity.WARNING,
                )
        }

        // 2. Every process should have at least one EndEvent (WARNING)
        val hasEnd = process.flowNodes.any { it is BpmnEvent && it.position == EventPosition.END }
        if (!hasEnd) {
            violations +=
                ConstraintViolation(
                    elementId = process.id,
                    message = "Process '${process.name ?: process.id}' has no EndEvent",
                    severity = ViolationSeverity.WARNING,
                )
        }

        // 3 & 4. SequenceFlow source and target must exist in this process (ERROR)
        process.sequenceFlows.forEach { flow ->
            if (flow.sourceRef !in nodeIds) {
                violations +=
                    ConstraintViolation(
                        elementId = flow.id,
                        message = "SequenceFlow '${flow.id}' source '${flow.sourceRef}' not found in process '${process.id}'",
                        severity = ViolationSeverity.ERROR,
                    )
            }
            if (flow.targetRef !in nodeIds) {
                violations +=
                    ConstraintViolation(
                        elementId = flow.id,
                        message = "SequenceFlow '${flow.id}' target '${flow.targetRef}' not found in process '${process.id}'",
                        severity = ViolationSeverity.ERROR,
                    )
            }
        }

        // 5. XOR/OR gateway with multiple outgoing flows and no defaultFlow (WARNING)
        process.flowNodes
            .filterIsInstance<BpmnGateway>()
            .filter { it.gatewayType == GatewayType.EXCLUSIVE || it.gatewayType == GatewayType.INCLUSIVE }
            .forEach { gw ->
                val outFlowCount = process.sequenceFlows.count { it.sourceRef == gw.id }
                if (outFlowCount > 1 && gw.defaultFlow == null) {
                    violations +=
                        ConstraintViolation(
                            elementId = gw.id,
                            message =
                                "Gateway '${gw.name ?: gw.id}' has $outFlowCount outgoing flows " +
                                    "but no defaultFlow set",
                            severity = ViolationSeverity.WARNING,
                        )
                }
            }

        // 6. Boundary event's attachedToRef must exist in the process (ERROR)
        process.flowNodes
            .filterIsInstance<BpmnEvent>()
            .filter { it.attachedToRef != null }
            .forEach { event ->
                if (event.attachedToRef !in nodeIds) {
                    violations +=
                        ConstraintViolation(
                            elementId = event.id,
                            message =
                                "BoundaryEvent '${event.name ?: event.id}' attachedToRef " +
                                    "'${event.attachedToRef}' not found in process '${process.id}'",
                            severity = ViolationSeverity.ERROR,
                        )
                }
            }
    }

    private fun checkCollaboration(
        collab: BpmnCollaboration,
        model: BpmnModel,
        violations: MutableList<ConstraintViolation>,
    ) {
        val processIds = model.processes.map { it.id }.toSet()

        // 7. MessageFlow with identical source and target (ERROR)
        collab.messageFlows.forEach { mf ->
            if (mf.sourceRef == mf.targetRef) {
                violations +=
                    ConstraintViolation(
                        elementId = mf.id,
                        message = "MessageFlow '${mf.id}' source and target are identical ('${mf.sourceRef}')",
                        severity = ViolationSeverity.ERROR,
                    )
            }
        }

        // 8. Participant.processRef must reference an existing process (ERROR)
        collab.participants
            .filter { it.processRef != null }
            .forEach { participant ->
                if (participant.processRef !in processIds) {
                    violations +=
                        ConstraintViolation(
                            elementId = participant.id,
                            message =
                                "Participant '${participant.name ?: participant.id}' processRef " +
                                    "'${participant.processRef}' not found in model",
                            severity = ViolationSeverity.ERROR,
                        )
                }
            }
    }

    private fun checkChoreography(
        choreography: BpmnChoreography,
        violations: MutableList<ConstraintViolation>,
    ) {
        val nodeIds =
            (choreography.tasks.map { it.id } + choreography.gateways.map { it.id } + choreography.events.map { it.id })
                .toSet()

        // 9. SequenceFlow source/target must exist in this choreography (ERROR)
        choreography.sequenceFlows.forEach { flow ->
            if (flow.sourceRef !in nodeIds) {
                violations +=
                    ConstraintViolation(
                        elementId = flow.id,
                        message =
                            "ChoreographySequenceFlow '${flow.id}' source '${flow.sourceRef}' " +
                                "not found in choreography '${choreography.id}'",
                        severity = ViolationSeverity.ERROR,
                    )
            }
            if (flow.targetRef !in nodeIds) {
                violations +=
                    ConstraintViolation(
                        elementId = flow.id,
                        message =
                            "ChoreographySequenceFlow '${flow.id}' target '${flow.targetRef}' " +
                                "not found in choreography '${choreography.id}'",
                        severity = ViolationSeverity.ERROR,
                    )
            }
        }

        // 10. At least one StartEvent without incoming flows (WARNING)
        val targetIds = choreography.sequenceFlows.map { it.targetRef }.toSet()
        val startEvents = choreography.events.filter { it.position == EventPosition.START }
        val hasFreeStart = startEvents.any { it.id !in targetIds }
        if (startEvents.isEmpty() || !hasFreeStart) {
            violations +=
                ConstraintViolation(
                    elementId = choreography.id,
                    message =
                        "Choreography '${choreography.name ?: choreography.id}' has no StartEvent " +
                            "without incoming flows",
                    severity = ViolationSeverity.WARNING,
                )
        }

        // 11. EndEvents must have no outgoing flows (ERROR)
        val sourceIds = choreography.sequenceFlows.map { it.sourceRef }.toSet()
        choreography.events
            .filter { it.position == EventPosition.END }
            .forEach { endEvent ->
                if (endEvent.id in sourceIds) {
                    violations +=
                        ConstraintViolation(
                            elementId = endEvent.id,
                            message =
                                "EndEvent '${endEvent.name ?: endEvent.id}' has outgoing flows, " +
                                    "which is not allowed",
                            severity = ViolationSeverity.ERROR,
                        )
                }
            }

        // 12. Every element should be reachable from a StartEvent (WARNING)
        val reachable = mutableSetOf<String>()
        val queue = ArrayDeque(startEvents.map { it.id })
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!reachable.add(current)) continue
            choreography.sequenceFlows
                .filter { it.sourceRef == current }
                .forEach { queue.addLast(it.targetRef) }
        }
        nodeIds
            .filterNot { it in reachable }
            .forEach { isolatedId ->
                violations +=
                    ConstraintViolation(
                        elementId = isolatedId,
                        message = "Choreography element '$isolatedId' is not reachable from any StartEvent",
                        severity = ViolationSeverity.WARNING,
                    )
            }

        // 13. condition set on a flow leaving a non-EXCLUSIVE/INCLUSIVE gateway (WARNING)
        val branchingGatewayIds =
            choreography.gateways
                .filter { it.type == GatewayType.EXCLUSIVE || it.type == GatewayType.INCLUSIVE }
                .map { it.id }
                .toSet()
        choreography.sequenceFlows
            .filter { it.condition != null }
            .forEach { flow ->
                if (flow.sourceRef !in branchingGatewayIds) {
                    violations +=
                        ConstraintViolation(
                            elementId = flow.id,
                            message =
                                "ChoreographySequenceFlow '${flow.id}' has a condition but does not " +
                                    "leave an EXCLUSIVE/INCLUSIVE gateway",
                            severity = ViolationSeverity.WARNING,
                        )
                }
            }

        // 14. Initiating message's participantRef must match task.initiatingParticipant (ERROR)
        choreography.tasks.forEach { task ->
            task.messageFlows
                .filter { it.isInitiating }
                .forEach { message ->
                    if (message.participantRef != task.initiatingParticipant) {
                        violations +=
                            ConstraintViolation(
                                elementId = task.id,
                                message =
                                    "ChoreographyTask '${task.name ?: task.id}' initiating message " +
                                        "participantRef '${message.participantRef}' does not match " +
                                        "initiatingParticipant '${task.initiatingParticipant}'",
                                severity = ViolationSeverity.ERROR,
                            )
                    }
                }
        }

        // 15. Participant-band continuity between directly connected tasks (WARNING)
        val tasksById = choreography.tasks.associateBy { it.id }
        choreography.sequenceFlows.forEach { flow ->
            val fromTask = tasksById[flow.sourceRef]
            val toTask = tasksById[flow.targetRef]
            if (fromTask != null && toTask != null) {
                val sharedParticipant = fromTask.participants.any { it in toTask.participants }
                if (!sharedParticipant) {
                    violations +=
                        ConstraintViolation(
                            elementId = flow.id,
                            message =
                                "Choreography tasks '${fromTask.name ?: fromTask.id}' and " +
                                    "'${toTask.name ?: toTask.id}' share no participant (band continuity broken)",
                            severity = ViolationSeverity.WARNING,
                        )
                }
            }
        }
    }
}
