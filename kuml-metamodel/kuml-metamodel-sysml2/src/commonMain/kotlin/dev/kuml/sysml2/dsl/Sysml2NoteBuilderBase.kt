package dev.kuml.sysml2.dsl

import dev.kuml.sysml2.Sysml2Comment
import dev.kuml.sysml2.Sysml2Element
import dev.kuml.sysml2.Sysml2LayoutFlow
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

    private var layoutSpec: Sysml2LayoutSpec? = null

    /**
     * Layout steering hints for this diagram (wave C3):
     *
     * ```
     * layout {
     *     direction = Right
     *     wrapColumns = 4
     *     sameLayer(a, b)
     *     first(start)
     *     last(end)
     * }
     * ```
     * Several `layout` blocks merge. Hard bounds are checked by `Sysml2LayoutHintChecker`.
     */
    fun layout(block: Sysml2LayoutBuilder.() -> Unit) {
        val built = Sysml2LayoutBuilder().apply(block).build()
        val prev = layoutSpec
        layoutSpec =
            if (prev == null) {
                built
            } else {
                Sysml2LayoutSpec(
                    direction = built.direction ?: prev.direction,
                    wrapColumns = built.wrapColumns ?: prev.wrapColumns,
                    sameLayer = prev.sameLayer + built.sameLayer,
                    first = prev.first + built.first,
                    last = prev.last + built.last,
                )
            }
    }

    internal fun layoutSpec(): Sysml2LayoutSpec? = layoutSpec?.takeUnless { it.isEmpty }

    internal fun noteIds(): List<String> = noteIds.distinct()
}

/** Receiver of a diagram's `layout { }` block (wave C3). The four flow values are members so no import is needed. */
@Sysml2Dsl
class Sysml2LayoutBuilder internal constructor() {
    /** Flow directions, usable as `direction = Right`. */
    @Suppress("ktlint:standard:property-naming")
    val Down: Sysml2LayoutFlow = Sysml2LayoutFlow.Down

    @Suppress("ktlint:standard:property-naming")
    val Right: Sysml2LayoutFlow = Sysml2LayoutFlow.Right

    @Suppress("ktlint:standard:property-naming")
    val Up: Sysml2LayoutFlow = Sysml2LayoutFlow.Up

    @Suppress("ktlint:standard:property-naming")
    val Left: Sysml2LayoutFlow = Sysml2LayoutFlow.Left

    var direction: Sysml2LayoutFlow? = null
    var wrapColumns: Int? = null
    private val sameLayer = mutableListOf<List<String>>()
    private val first = mutableListOf<String>()
    private val last = mutableListOf<String>()

    /** Places the given elements in the same layer. */
    fun sameLayer(vararg elements: Sysml2Element) {
        sameLayer += elements.map { it.id }
    }

    /** Id variant of [sameLayer]. */
    fun sameLayerById(vararg ids: String) {
        sameLayer += ids.toList()
    }

    /** Pins the given elements to the first layer. */
    fun first(vararg elements: Sysml2Element) {
        first += elements.map { it.id }
    }

    fun firstById(vararg ids: String) {
        first += ids.toList()
    }

    /** Pins the given elements to the last layer. */
    fun last(vararg elements: Sysml2Element) {
        last += elements.map { it.id }
    }

    fun lastById(vararg ids: String) {
        last += ids.toList()
    }

    internal fun build(): Sysml2LayoutSpec =
        Sysml2LayoutSpec(
            direction = direction,
            wrapColumns = wrapColumns,
            sameLayer = sameLayer.filter { it.isNotEmpty() },
            first = first.toList(),
            last = last.toList(),
        )
}
