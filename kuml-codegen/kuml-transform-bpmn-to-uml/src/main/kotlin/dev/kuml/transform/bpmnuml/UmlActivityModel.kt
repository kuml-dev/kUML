package dev.kuml.transform.bpmnuml

import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode

/**
 * Intermediate in-memory representation of a UML Activity diagram produced
 * during BPMN-to-UML transformation.
 *
 * Decouples the mapper ([BpmnToUmlActivityMapper]) from the script renderer
 * ([UmlActivityScriptRenderer]) and makes round-trip tests possible on a
 * typed structure rather than on emitted text.
 *
 * @property name Human-readable diagram name (derived from [dev.kuml.bpmn.model.BpmnProcess.name]).
 * @property nodes All activity nodes in declaration order.
 * @property edges All activity edges in declaration order.
 */
internal data class UmlActivityModel(
    val name: String,
    val nodes: List<UmlActivityNode>,
    val edges: List<UmlActivityEdge>,
)
