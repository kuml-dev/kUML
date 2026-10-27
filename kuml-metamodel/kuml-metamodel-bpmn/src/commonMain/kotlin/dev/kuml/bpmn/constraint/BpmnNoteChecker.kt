package dev.kuml.bpmn.constraint

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.core.model.note.KumlNoteIssue

/**
 * Structural checks for BPMN text annotations and associations.
 *
 *  - `BPMN_ASSOCIATION_UNKNOWN_REF` (error): unknown annotation or target id.
 *  - `BPMN_ASSOCIATION_UNSUPPORTED_TARGET` (warning): the target exists but is a
 *    pool, lane or sequence flow; the association is not rendered.
 */
object BpmnNoteChecker {
    const val UNKNOWN_REF: String = "BPMN_ASSOCIATION_UNKNOWN_REF"
    const val UNSUPPORTED_TARGET: String = "BPMN_ASSOCIATION_UNSUPPORTED_TARGET"

    fun check(model: BpmnModel): List<KumlNoteIssue> {
        val associations = model.processes.flatMap { it.associations } + model.collaborations.flatMap { it.associations }
        if (associations.isEmpty()) return emptyList()
        val annotationIds =
            (model.processes.flatMap { it.textAnnotations } + model.collaborations.flatMap { it.textAnnotations })
                .map { it.id }
                .toSet()
        val anchorable = model.allAnchorableIds()
        val issues = mutableListOf<KumlNoteIssue>()
        for (a in associations) {
            if (a.link.noteId !in annotationIds) {
                issues +=
                    KumlNoteIssue(
                        code = UNKNOWN_REF,
                        severity = KumlNoteIssue.Severity.ERROR,
                        subjectId = a.id,
                        message = "Association '${a.id}' refers to unknown text annotation '${a.link.noteId}'",
                    )
            }
            val target = a.link.targetId
            if (target !in anchorable) {
                if (model.elementById(target) == null) {
                    issues +=
                        KumlNoteIssue(
                            code = UNKNOWN_REF,
                            severity = KumlNoteIssue.Severity.ERROR,
                            subjectId = a.id,
                            message = "Association '${a.id}' refers to unknown element '$target'",
                        )
                } else {
                    issues +=
                        KumlNoteIssue(
                            code = UNSUPPORTED_TARGET,
                            severity = KumlNoteIssue.Severity.WARNING,
                            subjectId = a.id,
                            message = "Association '${a.id}' targets '$target', which is a pool, lane or flow; it is not rendered",
                        )
                }
            }
        }
        return issues
    }
}
