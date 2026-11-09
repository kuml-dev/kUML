package dev.kuml.cli

import dev.kuml.bpmn.constraint.BpmnNoteChecker
import dev.kuml.core.model.note.KumlNoteCodes
import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.erm.constraint.ErmNoteChecker
import dev.kuml.layout.bridge.NoteDiagnostics
import dev.kuml.layout.bridge.note.UndrawnNote
import dev.kuml.sysml2.constraint.Sysml2NoteChecker

/**
 * Structural note findings (unknown anchors, ...) plus display-limit warnings
 * (`NOTE_TRUNCATED`) for the diagram that was extracted from a script. Shared by
 * `render` (printed as warnings) and `validate` (folded into the result).
 *
 * Messages name note ids and limits, never note bodies.
 */
public object NoteChecks {
    public data class Finding(
        val code: String,
        val isError: Boolean,
        val subjectId: String,
        val message: String,
        /** Script line of the note the finding is about (1-based), `null` when unknown. */
        val sourceLine: Int? = null,
    )

    /** The `KUML-NOTE-W002` findings for notes a render declared but did not draw (see [NoteUndrawnCollector]). */
    public fun undrawn(
        undrawn: List<UndrawnNote>,
        alreadyReported: List<Finding> = emptyList(),
    ): List<Finding> {
        val reported = alreadyReported.filter { it.code == KumlNoteCodes.ANCHOR_NOT_DRAWABLE || it.code == KumlNoteCodes.ANCHOR_UNKNOWN }
        return undrawn
            .filter { u -> reported.none { it.subjectId == u.noteId } }
            .map { u ->
                Finding(
                    code = KumlNoteCodes.ANCHOR_NOT_DRAWABLE,
                    isError = false,
                    subjectId = u.noteId,
                    message = u.message(),
                    sourceLine = u.sourceLine,
                )
            }
    }

    public fun of(extracted: ExtractedDiagram): List<Finding> {
        val issues: List<KumlNoteIssue>
        val truncated =
            when (extracted) {
                is ExtractedDiagram.Sysml2 -> {
                    issues = Sysml2NoteChecker.check(model = extracted.model, diagram = extracted.diagram)
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
                is ExtractedDiagram.C4 -> {
                    issues =
                        dev.kuml.layout.bridge.note.C4NotePlacement
                            .unknownAnchors(extracted.model)
                            .map { (noteId, anchorId) ->
                                KumlNoteIssue(
                                    code = KumlNoteCodes.ANCHOR_UNKNOWN,
                                    severity = KumlNoteIssue.Severity.WARNING,
                                    subjectId = noteId,
                                    message = "Anker unbekannt: $anchorId (Notiz $noteId)",
                                )
                            }
                    NoteDiagnostics.forBodies(extracted.model.notes.map { it.id to it.body })
                }
                is ExtractedDiagram.Blueprint -> {
                    val known =
                        extracted.model.steps
                            .map { it.id }
                            .toSet()
                    issues =
                        extracted.model.noteLinks
                            .filter { it.targetId !in known }
                            .map {
                                KumlNoteIssue(
                                    code = KumlNoteCodes.ANCHOR_UNKNOWN,
                                    severity = KumlNoteIssue.Severity.WARNING,
                                    subjectId = it.noteId,
                                    message = "Anker unbekannt: ${it.targetId} (Notiz ${it.noteId})",
                                )
                            }
                    NoteDiagnostics.forBodies(extracted.model.notes.map { it.id to it.body })
                }
                else -> {
                    issues = emptyList()
                    emptyList()
                }
            }
        val lines = noteLines(extracted)
        val structural =
            issues.map {
                Finding(
                    code = it.code,
                    isError = it.severity == KumlNoteIssue.Severity.ERROR,
                    subjectId = it.subjectId,
                    message = it.message,
                    sourceLine = it.sourceLine ?: lines[it.subjectId],
                )
            }
        val display =
            truncated.map {
                Finding(code = it.code, isError = false, subjectId = it.noteId, message = it.message, sourceLine = lines[it.noteId])
            }
        return structural + display
    }

    /** Script line of each note of [extracted] (note id to line); notes without a known line are absent. */
    private fun noteLines(extracted: ExtractedDiagram): Map<String, Int> {
        val pairs: List<Pair<String, Int?>> =
            when (extracted) {
                is ExtractedDiagram.Sysml2 -> extracted.model.comments.map { it.id to it.note.sourceLine }
                is ExtractedDiagram.Bpmn ->
                    (
                        extracted.model.processes.flatMap { it.textAnnotations } +
                            extracted.model.collaborations.flatMap { it.textAnnotations }
                    ).map { it.id to it.note.sourceLine }
                is ExtractedDiagram.Erm -> extracted.model.notes.map { it.id to it.note.sourceLine }
                is ExtractedDiagram.C4 -> extracted.model.notes.map { it.id to it.sourceLine }
                is ExtractedDiagram.Blueprint -> extracted.model.notes.map { it.id to it.sourceLine }
                else -> emptyList()
            }
        return pairs.mapNotNull { (id, line) -> line?.let { id to it } }.toMap()
    }
}
