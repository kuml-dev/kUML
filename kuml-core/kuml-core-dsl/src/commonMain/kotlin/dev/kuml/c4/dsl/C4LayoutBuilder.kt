package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Element
import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.layout.LayoutSpecBuilder

/**
 * Receiver of a C4 diagram's `layout { }` block (PAL wave 2B). Element overloads sit on top of the
 * shared id-string overloads of [LayoutSpecBuilder].
 *
 * ```
 * containerDiagram(system) {
 *     layout {
 *         direction = Right
 *         wrapColumns = 3
 *     }
 * }
 * ```
 */
@KumlDsl
class C4LayoutBuilder internal constructor() : LayoutSpecBuilder() {
    /** Places the given elements in the same layer. */
    fun sameLayer(vararg elements: C4Element) {
        addSameLayer(elements.map { it.id })
    }

    /** Pins the given elements to the first layer. */
    fun first(vararg elements: C4Element) {
        addFirst(elements.map { it.id })
    }

    /** Pins the given elements to the last layer. */
    fun last(vararg elements: C4Element) {
        addLast(elements.map { it.id })
    }
}
