package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Element
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink

/**
 * Collects the notes of one C4 model: those declared on the model and those declared inside a diagram block.
 * Ids are `note-<n>` in declaration order (or the explicit id), so output is deterministic.
 */
class C4NoteRegistry {
    val notes: MutableList<KumlNote> = mutableListOf()
    val links: MutableList<KumlNoteLink> = mutableListOf()

    /** Registers a note anchored to [anchors] and returns its id. */
    fun add(
        text: String,
        anchors: List<C4Element>,
        id: String?,
    ): String {
        val noteId = id ?: "note-${notes.size + 1}"
        require(notes.none { it.id == noteId }) { "duplicate note id '$noteId'" }
        notes += KumlNote(id = noteId, body = text)
        for (a in anchors) links += KumlNoteLink(id = "note-link:$noteId::${a.id}", noteId = noteId, targetId = a.id)
        return noteId
    }
}
