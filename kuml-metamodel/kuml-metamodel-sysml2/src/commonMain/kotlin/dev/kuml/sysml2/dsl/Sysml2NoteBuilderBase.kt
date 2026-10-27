package dev.kuml.sysml2.dsl

import dev.kuml.sysml2.Sysml2Comment
import dev.kuml.sysml2.Sysml2Element

/**
 * Shared note verbs for every SysML 2 diagram builder. A comment created inside
 * a diagram block is stored in the model and listed in that diagram's
 * `noteIds`.
 */
@Sysml2Dsl
abstract class Sysml2NoteBuilderBase internal constructor(
    private val modelBuilder: Sysml2ModelBuilder,
) {
    private val noteIds = mutableListOf<String>()

    /** Adds a comment anchored to the elements with the given ids (none = free-standing). */
    fun comment(
        text: String,
        vararg anchors: String,
        id: String? = null,
    ): Sysml2Comment {
        val c = modelBuilder.addComment(text = text, anchorIds = anchors.toList(), id = id)
        noteIds += c.id
        return c
    }

    /** Adds a comment anchored to the given elements (typed handle overload). */
    fun comment(
        text: String,
        firstAnchor: Sysml2Element,
        vararg moreAnchors: Sysml2Element,
        id: String? = null,
    ): Sysml2Comment = comment(text = text, *(listOf(firstAnchor) + moreAnchors).map { it.id }.toTypedArray(), id = id)

    /** Alias of [comment]. */
    fun note(
        text: String,
        vararg anchors: String,
        id: String? = null,
    ): Sysml2Comment = comment(text = text, *anchors, id = id)

    /** Alias of [comment]. */
    fun note(
        text: String,
        firstAnchor: Sysml2Element,
        vararg moreAnchors: Sysml2Element,
        id: String? = null,
    ): Sysml2Comment = comment(text = text, firstAnchor = firstAnchor, *moreAnchors, id = id)

    /** Lists a model-level comment in this diagram. */
    fun include(comment: Sysml2Comment) {
        noteIds += comment.id
    }

    /** Lists a comment by id in this diagram (forward refs). */
    fun includeCommentById(id: String) {
        noteIds += id
    }

    internal fun noteIds(): List<String> = noteIds.distinct()
}
