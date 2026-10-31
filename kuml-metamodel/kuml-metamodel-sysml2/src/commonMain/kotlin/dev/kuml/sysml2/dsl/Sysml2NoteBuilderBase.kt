package dev.kuml.sysml2.dsl

import dev.kuml.core.model.layout.LayoutSpecBuilder
import dev.kuml.core.model.layout.LayoutSpecCollector
import dev.kuml.sysml2.Sysml2Comment
import dev.kuml.sysml2.Sysml2Element
import dev.kuml.sysml2.Sysml2LayoutSpec

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

    private val layoutCollector = LayoutSpecCollector()

    /**
     * Layout steering hints for this diagram:
     *
     * ```
     * layout {
     *     direction = Right
     *     wrapColumns = 4
     *     sameLayer(a, b)          // elements or id strings
     *     first(start)
     *     last(end)
     *     maxWidth = 1800
     *     aspectRatio = 1.6
     * }
     * ```
     * Several `layout` blocks merge. Hard bounds are checked by `Sysml2LayoutHintChecker`.
     */
    fun layout(block: Sysml2LayoutBuilder.() -> Unit) {
        layoutCollector.add(Sysml2LayoutBuilder().apply(block).build())
    }

    internal fun layoutSpec(): Sysml2LayoutSpec? = layoutCollector.result()

    internal fun noteIds(): List<String> = noteIds.distinct()
}

/** Receiver of a SysML 2 diagram's `layout { }` block. Element overloads on top of the shared id-string ones. */
@Sysml2Dsl
class Sysml2LayoutBuilder internal constructor() : LayoutSpecBuilder() {
    /** Places the given elements in the same layer. */
    fun sameLayer(vararg elements: Sysml2Element) {
        addSameLayer(elements.map { it.id })
    }

    /** Id variant of [sameLayer]. */
    fun sameLayerById(vararg ids: String) {
        addSameLayer(ids.toList())
    }

    /** Pins the given elements to the first layer. */
    fun first(vararg elements: Sysml2Element) {
        addFirst(elements.map { it.id })
    }

    fun firstById(vararg ids: String) {
        addFirst(ids.toList())
    }

    /** Pins the given elements to the last layer. */
    fun last(vararg elements: Sysml2Element) {
        addLast(elements.map { it.id })
    }

    fun lastById(vararg ids: String) {
        addLast(ids.toList())
    }
}
