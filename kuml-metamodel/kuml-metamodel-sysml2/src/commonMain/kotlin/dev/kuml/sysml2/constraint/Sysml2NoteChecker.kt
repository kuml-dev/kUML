package dev.kuml.sysml2.constraint

import dev.kuml.core.model.note.KumlNoteCodes
import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model

/**
 * Structural checks for SysML 2 comments.
 *
 *  - `SYSML2_NOTE_ANCHOR_UNKNOWN` (error): a link points to an unknown note.
 *  - `KUML-NOTE-W001` (warning): a link anchors an unknown element. The note is shown free-standing.
 *  - `KUML-NOTE-W002` (warning, [check] with a diagram): an explicitly listed note has an anchor this
 *    diagram kind cannot draw ([Sysml2NoteAnchors]).
 *  - `KUML-NOTE-W003` (warning): more than 8 anchors on one note or more than 200 notes.
 *  - `SYSML2_NOTE_DUPLICATE_ID` (error): a comment id collides with another element or comment.
 *  - `SYSML2_NOTE_NOT_SHOWN` (warning): an unanchored comment is listed in no diagram's `noteIds`.
 */
public object Sysml2NoteChecker {
    public const val ANCHOR_UNKNOWN: String = "SYSML2_NOTE_ANCHOR_UNKNOWN"
    public const val DUPLICATE_ID: String = "SYSML2_NOTE_DUPLICATE_ID"
    public const val NOT_SHOWN: String = "SYSML2_NOTE_NOT_SHOWN"

    public fun check(model: Sysml2Model): List<KumlNoteIssue> {
        if (model.comments.isEmpty() && model.commentLinks.isEmpty()) return emptyList()
        val issues = mutableListOf<KumlNoteIssue>()
        val noteIds = model.comments.map { it.id }
        val anchorable = model.allAnchorableIds()

        val seen = mutableSetOf<String>()
        for (id in noteIds) {
            if (id in anchorable || !seen.add(id)) {
                issues +=
                    KumlNoteIssue(
                        code = DUPLICATE_ID,
                        severity = KumlNoteIssue.Severity.ERROR,
                        subjectId = id,
                        message = "Comment id '$id' is not unique in the model",
                    )
            }
        }
        for (l in model.commentLinks) {
            if (l.link.noteId !in noteIds) {
                issues +=
                    KumlNoteIssue(
                        code = ANCHOR_UNKNOWN,
                        severity = KumlNoteIssue.Severity.ERROR,
                        subjectId = l.id,
                        message = "Comment link '${l.id}' refers to unknown comment '${l.link.noteId}'",
                    )
            }
            if (l.link.targetId !in anchorable) {
                issues +=
                    KumlNoteIssue(
                        code = KumlNoteCodes.ANCHOR_UNKNOWN,
                        severity = KumlNoteIssue.Severity.WARNING,
                        subjectId = l.link.noteId,
                        message = "Anker unbekannt: ${l.link.targetId} (Notiz ${l.link.noteId})",
                    )
            }
        }
        val anchored = model.commentLinks.map { it.link.noteId }.toSet()
        val listed = model.diagrams.flatMap { it.noteIds }.toSet()
        for (c in model.comments) {
            if (c.id !in anchored && c.id !in listed) {
                issues +=
                    KumlNoteIssue(
                        code = NOT_SHOWN,
                        severity = KumlNoteIssue.Severity.WARNING,
                        subjectId = c.id,
                        message = "Comment '${c.id}' has no anchor and is listed in no diagram, so it is not shown",
                    )
            }
        }
        issues += limitIssues(model)
        return issues
    }

    /**
     * Like [check], plus `KUML-NOTE-W002` for the notes [diagram] lists explicitly whose anchors
     * the diagram kind cannot draw. Notes that are only visible through another anchor stay silent.
     */
    public fun check(
        model: Sysml2Model,
        diagram: Sysml2Diagram?,
    ): List<KumlNoteIssue> {
        val base = check(model)
        if (diagram == null) return base
        val supported = Sysml2NoteAnchors.supported(diagram)
        val owner = Sysml2NoteAnchors.ownerOf(diagram)
        val listed = diagram.noteIds.toSet()
        val extra = mutableListOf<KumlNoteIssue>()
        for (l in model.commentLinks) {
            if (l.link.noteId !in listed) continue
            val kind = Sysml2NoteAnchors.kindOf(model = model, id = l.link.targetId, ownerId = owner) ?: continue
            if (kind !in supported) {
                extra +=
                    KumlNoteIssue(
                        code = KumlNoteCodes.ANCHOR_NOT_DRAWABLE,
                        severity = KumlNoteIssue.Severity.WARNING,
                        subjectId = l.link.noteId,
                        message =
                            "Anker in diesem Diagramm nicht darstellbar: ${l.link.targetId} " +
                                "(Notiz ${l.link.noteId}, Diagramm ${diagram.name})",
                    )
            }
        }
        return base + extra
    }

    private fun limitIssues(model: Sysml2Model): List<KumlNoteIssue> {
        val out = mutableListOf<KumlNoteIssue>()
        model.commentLinks.groupBy { it.link.noteId }.forEach { (noteId, links) ->
            if (links.size > KumlNoteCodes.MAX_ANCHORS_PER_NOTE) {
                out +=
                    KumlNoteIssue(
                        code = KumlNoteCodes.LIMIT_APPLIED,
                        severity = KumlNoteIssue.Severity.WARNING,
                        subjectId = noteId,
                        message =
                            "Notiz $noteId hat ${links.size} Anker, es werden nur die ersten " +
                                "${KumlNoteCodes.MAX_ANCHORS_PER_NOTE} gezeichnet",
                    )
            }
        }
        if (model.comments.size > KumlNoteCodes.MAX_NOTES_PER_DIAGRAM) {
            out +=
                KumlNoteIssue(
                    code = KumlNoteCodes.LIMIT_APPLIED,
                    severity = KumlNoteIssue.Severity.WARNING,
                    subjectId = model.name,
                    message =
                        "Das Modell hat ${model.comments.size} Notizen, es werden nur die ersten " +
                            "${KumlNoteCodes.MAX_NOTES_PER_DIAGRAM} gezeichnet",
                )
        }
        return out
    }
}
