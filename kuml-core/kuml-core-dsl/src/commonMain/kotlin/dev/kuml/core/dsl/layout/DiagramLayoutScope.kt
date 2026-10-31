package dev.kuml.core.dsl.layout

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.layout.LayoutSpecBuilder
import dev.kuml.core.model.layout.LayoutSpecCollector
import dev.kuml.uml.UmlElement

/**
 * Receiver of a UML diagram's `layout { }` block (PAL wave 2B). Element overloads sit on top of the
 * shared id-string overloads of [LayoutSpecBuilder].
 *
 * Not to be confused with the *element-level* `layout { col = 2 }` block of [LayoutHintsScope].
 */
@KumlDsl
public class UmlLayoutBuilder internal constructor() : LayoutSpecBuilder() {
    /** Places the given elements in the same layer. */
    public fun sameLayer(vararg elements: UmlElement) {
        addSameLayer(elements.map { it.id })
    }

    /** Pins the given elements to the first layer. */
    public fun first(vararg elements: UmlElement) {
        addFirst(elements.map { it.id })
    }

    /** Pins the given elements to the last layer. */
    public fun last(vararg elements: UmlElement) {
        addLast(elements.map { it.id })
    }
}

/**
 * Implemented by every UML diagram builder: gives the diagram a `layout { }` block that is stored as
 * `KumlDiagram.layout`. Several blocks merge.
 *
 * ```
 * classDiagram("Zoo") {
 *     layout {
 *         wrapColumns = 3
 *         aspectRatio = 1.6
 *     }
 * }
 * ```
 */
@KumlDsl
public interface DiagramLayoutScope {
    /** Collects the diagram's layout blocks; read by `build()`. */
    public val layoutCollector: LayoutSpecCollector
}
