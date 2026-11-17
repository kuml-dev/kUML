package dev.kuml.cli

import dev.kuml.bpmn.constraint.BpmnNoteChecker
import dev.kuml.core.model.note.KumlNoteCodes
import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.core.model.note.isHint
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.erm.constraint.ErmNoteChecker
import dev.kuml.layout.bridge.NoteDiagnostics
import dev.kuml.layout.bridge.note.PlacementHintMiss
import dev.kuml.layout.bridge.note.UndrawnNote
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.constraint.Sysml2NoteChecker
import dev.kuml.uml.UmlComment

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

    /** The `KUML-NOTE-W004` findings for placement hints a render could not honour (see [dev.kuml.layout.bridge.note.NotePlacementMissCollector]). */
    public fun placementMisses(misses: List<PlacementHintMiss>): List<Finding> =
        misses.map { m ->
            Finding(
                code = KumlNoteCodes.PLACEMENT_NOT_HONOURED,
                isError = false,
                subjectId = m.noteId,
                message = m.message(),
                sourceLine = m.sourceLine,
            )
        }

    /**
     * `KUML-NOTE-W005`: a side hint (ABOVE/BELOW/LEFT/RIGHT) on a note of a diagram type whose placement does not evaluate
     * it. Decidable without a layout, so `validate` reports it as well. NEAR and unset hints never warn. SysML 2
     * (except SEQ), C4, ERM and UML (except sequence diagrams) evaluate the hint; SEQ (SysML 2 and UML), BLUEPRINT and BPMN ignore it.
     */
    private fun placementIgnored(extracted: ExtractedDiagram): List<Finding> {
        val family: String
        val notes: List<Triple<String, NotePlacement?, Int?>>
        when (extracted) {
            is ExtractedDiagram.Sysml2 -> {
                val d = extracted.diagram
                if (d !is SeqDiagram) return emptyList()
                val inScope =
                    d.noteIds.toSet() +
                        extracted.model.commentLinks
                            .filter { it.link.targetId in d.elementIds }
                            .map { it.link.noteId }
                family = "SEQ"
                notes =
                    extracted.model.comments
                        .filter { it.id in inScope }
                        .map { Triple(it.id, it.note.placement, it.note.sourceLine) }
            }
            is ExtractedDiagram.C4 -> return emptyList()
            is ExtractedDiagram.Blueprint -> {
                family = "BLUEPRINT"
                notes = extracted.model.notes.map { Triple(it.id, it.placement, it.sourceLine) }
            }
            is ExtractedDiagram.Bpmn -> {
                family = "BPMN"
                notes =
                    (
                        extracted.model.processes.flatMap { it.textAnnotations } +
                            extracted.model.collaborations.flatMap { it.textAnnotations }
                    ).map { Triple(it.id, it.note.placement, it.note.sourceLine) }
            }
            is ExtractedDiagram.Erm -> return emptyList()
            is ExtractedDiagram.Uml -> {
                // Nacharbeit 99: only UML sequence diagrams still ignore the hint (their notes are placed by the renderer, not by the placer).
                if (extracted.diagram.type != dev.kuml.core.model.DiagramType.SEQUENCE) return emptyList()
                family = "UML"
                notes =
                    extracted.diagram.elements
                        .filterIsInstance<UmlComment>()
                        .map { Triple(it.id, it.placement, null) }
            }
        }
        return notes
            .filter { (_, placement, _) -> placement.isHint() }
            .map { (id, _, line) ->
                Finding(
                    code = KumlNoteCodes.PLACEMENT_IGNORED,
                    isError = false,
                    subjectId = id,
                    message = "placement hint ignored in this diagram type: $id ($family)",
                    sourceLine = line,
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
        return structural + display + placementIgnored(extracted)
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
