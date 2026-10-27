package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import kotlinx.serialization.Serializable

/** Free-text note in an ERM diagram, drawn as a note symbol with a folded corner. */
@Serializable
data class ErmNote(
    val note: KumlNote,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement {
    override val id: String get() = note.id
    override val name: String? get() = null
}

/** Anchor link from an [ErmNote] to an [ErmEntity] or [ErmView]; dashed line, no arrowhead. */
@Serializable
data class ErmNoteLink(
    val link: KumlNoteLink,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement {
    override val id: String get() = link.id
    override val name: String? get() = null
}
