package dev.kuml.sysml2

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import kotlinx.serialization.Serializable

/**
 * Free-text comment (note) in a SysML 2 model. Rendered as a note symbol with a
 * folded corner. The text and the id live in the shared [KumlNote].
 *
 * Whether a comment appears in a diagram is decided by
 * [Sysml2Model.notesVisibleIn].
 */
@Serializable
data class Sysml2Comment(
    val note: KumlNote,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Element {
    override val id: String get() = note.id
}

/**
 * Anchor link from a [Sysml2Comment] to an annotated definition or usage.
 * Rendered as a dashed line without arrowhead.
 */
@Serializable
data class Sysml2CommentLink(
    val link: KumlNoteLink,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Element {
    override val id: String get() = link.id
}

/** Comments and links of a model that are shown in one diagram. */
data class Sysml2VisibleNotes(
    val comments: List<Sysml2Comment>,
    val links: List<Sysml2CommentLink>,
) {
    companion object {
        val EMPTY: Sysml2VisibleNotes = Sysml2VisibleNotes(comments = emptyList(), links = emptyList())
    }
}
