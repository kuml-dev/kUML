package dev.kuml.cli

import dev.kuml.bpmn.constraint.BpmnNoteChecker
import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.erm.constraint.ErmNoteChecker
import dev.kuml.layout.bridge.NoteDiagnostics
import dev.kuml.sysml2.constraint.Sysml2NoteChecker

/**
 * Structural note findings (unknown anchors, ...) plus display-limit warnings
 * (`NOTE_TRUNCATED`) for the diagram that was extracted from a script. Shared by
 * `render` (printed as warnings) and `validate` (folded into the result).
 *
 * Messages name note ids and limits, never note bodies.
 */
internal object NoteChecks {
    data class Finding(
        val code: String,
        val isError: Boolean,
        val subjectId: String,
        val message: String,
    )

    fun of(extracted: ExtractedDiagram): List<Finding> {
        val issues: List<KumlNoteIssue>
        val truncated =
            when (extracted) {
                is ExtractedDiagram.Sysml2 -> {
                    issues = Sysml2NoteChecker.check(extracted.model)
                    NoteDiagnostics.forSysml2(extracted.model)
                }
                is ExtractedDiagram.Bpmn -> {
                    issues = BpmnNoteChecker.check(extracted.model)
                    NoteDiagnostics.forBpmn(extracted.model)
                }
                is ExtractedDiagram.Erm -> {
                    issues = ErmNoteChecker.check(extracted.model)
                    NoteDiagnostics.forErm(extracted.model)
                }
                is ExtractedDiagram.Uml -> {
                    issues = emptyList()
                    NoteDiagnostics.forUml(extracted.diagram)
                }
                else -> {
                    issues = emptyList()
                    emptyList()
                }
            }
        val structural =
            issues.map {
                Finding(
                    code = it.code,
                    isError = it.severity == KumlNoteIssue.Severity.ERROR,
                    subjectId = it.subjectId,
                    message = it.message,
                )
            }
        val display = truncated.map { Finding(code = it.code, isError = false, subjectId = it.noteId, message = it.message) }
        return structural + display
    }
}
