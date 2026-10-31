package dev.kuml.core.model.layout

/**
 * Language-neutral receiver of a diagram's `layout { }` block. Subclasses add element-typed
 * overloads of [sameLayer] / [first] / [last] (each language has its own element type) and carry
 * their own DSL marker. The id-string variants live here, so every diagram family accepts
 * `sameLayer("A::b", "A::c")`.
 *
 * Empty `sameLayer()` calls are ignored.
 */
open class LayoutSpecBuilder protected constructor() {
    /** Flow directions, usable as `direction = Right`. */
    @Suppress("ktlint:standard:property-naming")
    val Down: LayoutFlow = LayoutFlow.Down

    @Suppress("ktlint:standard:property-naming")
    val Right: LayoutFlow = LayoutFlow.Right

    @Suppress("ktlint:standard:property-naming")
    val Up: LayoutFlow = LayoutFlow.Up

    @Suppress("ktlint:standard:property-naming")
    val Left: LayoutFlow = LayoutFlow.Left

    var direction: LayoutFlow? = null
    var wrapColumns: Int? = null

    /** Hard upper bound of the diagram width in px. */
    var maxWidth: Int? = null

    /** Soft target width / height. */
    var aspectRatio: Double? = null

    private val sameLayerGroups = mutableListOf<List<String>>()
    private val firstIds = mutableListOf<String>()
    private val lastIds = mutableListOf<String>()

    /** Places the elements with the given ids in the same layer. */
    fun sameLayer(vararg ids: String) {
        addSameLayer(ids.toList())
    }

    /** Pins the elements with the given ids to the first layer. */
    fun first(vararg ids: String) {
        addFirst(ids.toList())
    }

    /** Pins the elements with the given ids to the last layer. */
    fun last(vararg ids: String) {
        addLast(ids.toList())
    }

    protected fun addSameLayer(ids: List<String>) {
        if (ids.isNotEmpty()) sameLayerGroups += ids
    }

    protected fun addFirst(ids: List<String>) {
        firstIds += ids
    }

    protected fun addLast(ids: List<String>) {
        lastIds += ids
    }

    fun build(): LayoutSpec =
        LayoutSpec(
            direction = direction,
            wrapColumns = wrapColumns,
            sameLayer = sameLayerGroups.toList(),
            first = firstIds.toList(),
            last = lastIds.toList(),
            maxWidth = maxWidth,
            aspectRatio = aspectRatio,
        )
}

/** Collects several `layout { }` blocks of one diagram into one [LayoutSpec]. */
class LayoutSpecCollector {
    private var current: LayoutSpec? = null

    fun add(spec: LayoutSpec) {
        current = current?.mergedWith(spec) ?: spec
    }

    /** The merged spec, or `null` if nothing was set. */
    fun result(): LayoutSpec? = current?.takeUnless { it.isEmpty }
}
