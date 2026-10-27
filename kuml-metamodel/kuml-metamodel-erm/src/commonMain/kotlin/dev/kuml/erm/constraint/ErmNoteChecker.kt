package dev.kuml.erm.constraint

import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.erm.model.ErmModel

/**
 * Structural checks for ERM notes.
 *
 *  - `ERM_NOTE_ANCHOR_UNKNOWN` (error): a link refers to an unknown note or element.
 *  - `ERM_NOTE_ANCHOR_UNSUPPORTED` (error): the anchor exists but is neither an entity nor a view.
 */
object ErmNoteChecker {
    const val ANCHOR_UNKNOWN: String = "ERM_NOTE_ANCHOR_UNKNOWN"
    const val ANCHOR_UNSUPPORTED: String = "ERM_NOTE_ANCHOR_UNSUPPORTED"

    fun check(model: ErmModel): List<KumlNoteIssue> {
        if (model.noteLinks.isEmpty()) return emptyList()
        val noteIds = model.notes.map { it.id }.toSet()
        val anchorable = model.anchorableIds()
        val issues = mutableListOf<KumlNoteIssue>()
        for (l in model.noteLinks) {
            if (l.link.noteId !in noteIds) {
                issues +=
                    KumlNoteIssue(
                        code = ANCHOR_UNKNOWN,
                        severity = KumlNoteIssue.Severity.ERROR,
                        subjectId = l.id,
                        message = "Note link '${l.id}' refers to unknown note '${l.link.noteId}'",
                    )
            }
            val target = l.link.targetId
            if (target !in anchorable) {
                val code = if (model.elementById(target) == null) ANCHOR_UNKNOWN else ANCHOR_UNSUPPORTED
                val what = if (code == ANCHOR_UNKNOWN) "unknown element" else "an element that is neither entity nor view:"
                issues +=
                    KumlNoteIssue(
                        code = code,
                        severity = KumlNoteIssue.Severity.ERROR,
                        subjectId = l.id,
                        message = "Note link '${l.id}' anchors $what '$target'",
                    )
            }
        }
        return issues
    }
}
