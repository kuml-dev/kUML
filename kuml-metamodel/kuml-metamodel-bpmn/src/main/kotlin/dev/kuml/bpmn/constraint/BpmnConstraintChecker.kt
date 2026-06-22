package dev.kuml.bpmn.constraint

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
 *
 * V3.1.6
 */
public class BpmnConstraintChecker {
    /**
     * Checks all processes and collaborations in [model] and returns the list
     * of violations found. An empty list means the model passes all checks.
     */
    public fun check(model: BpmnModel): List<ConstraintViolation> =
        buildList {
            model.processes.forEach { process -> checkProcess(process, this) }
            model.collaborations.forEach { collab -> checkCollaboration(collab, model, this) }
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
}
