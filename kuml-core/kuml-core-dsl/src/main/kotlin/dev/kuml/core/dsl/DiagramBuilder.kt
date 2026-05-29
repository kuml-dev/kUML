package dev.kuml.core.dsl

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement

/**
 * Builder for a [KumlDiagram].
 *
 * Do not instantiate directly — use the [diagram] entry-point function.
 */
@KumlDsl
class DiagramBuilder(
    private val name: String,
    private val type: DiagramType,
) {
    private val elements = mutableListOf<KumlElement>()

    /** Builds the immutable [KumlDiagram]. */
    fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = type,
            elements = elements.toList(),
        )
}
