package dev.kuml.sysml2.constraint

import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.sysml2.Sysml2Model

/**
 * Structural checks for SysML 2 comments.
 *
 *  - `SYSML2_NOTE_ANCHOR_UNKNOWN` (error): a link points to an unknown note or target.
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
                        code = ANCHOR_UNKNOWN,
                        severity = KumlNoteIssue.Severity.ERROR,
                        subjectId = l.id,
                        message = "Comment link '${l.id}' anchors unknown element '${l.link.targetId}'",
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
        return issues
    }
}
