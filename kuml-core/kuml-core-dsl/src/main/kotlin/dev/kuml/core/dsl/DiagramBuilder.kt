package dev.kuml.core.dsl

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.dsl.UmlModelScope

/**
 * Builder for a [KumlDiagram].
 *
 * Implements [UmlModelScope] so that all UML element builders (`classOf`,
 * `interfaceOf`, `enumOf`, `` `package` ``, `association`, `generalization`,
 * `realization`, `dependency`) are available inside the `diagram { }` lambda.
 *
 * Do not instantiate directly — use the [diagram] entry-point function.
 */
@KumlDsl
class DiagramBuilder(
    private val name: String,
    private val type: DiagramType,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val elements = mutableListOf<KumlElement>()

    override fun addNamedElement(element: UmlNamedElement) {
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        elements += relationship
        takenIds += relationship.id
    }

    /** Builds the immutable [KumlDiagram]. */
    fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = type,
            elements = elements.toList(),
        )
}
