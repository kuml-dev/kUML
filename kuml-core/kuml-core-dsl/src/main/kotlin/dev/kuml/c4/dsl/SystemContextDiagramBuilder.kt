package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ElementId
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.core.dsl.KumlDsl

/**
 * Scope for building a System Context Diagram.
 *
 * System Context Diagrams show the system in scope and its relationships to users
 * and external systems. Only persons and software systems are allowed.
 */
@KumlDsl
interface SystemContextDiagramBuilder {
    var description: String?

    var showExternalRelationships: Boolean

    var showInternalRelationships: Boolean

    fun element(elem: C4Person): C4Person

    fun element(elem: C4SoftwareSystem): C4SoftwareSystem

    fun include(vararg elements: C4Element)

    fun exclude(vararg elements: C4Element)

    fun title(text: String)

    fun note(text: String)
}

/**
 * Implementation of SystemContextDiagramBuilder.
 *
 * Tracks included/excluded elements and validates that only persons and systems are used.
 */
@KumlDsl
class SystemContextDiagramBuilderImpl(
    private val parentModel: C4Model,
) : SystemContextDiagramBuilder {
    override var description: String? = null
    override var showExternalRelationships: Boolean = true
    override var showInternalRelationships: Boolean = true

    private val includedElements = mutableSetOf<ElementId>()
    private val excludedElements = mutableSetOf<ElementId>()

    override fun element(elem: C4Person): C4Person {
        validateElementType(elem)
        includedElements.add(elem.id)
        return elem
    }

    override fun element(elem: C4SoftwareSystem): C4SoftwareSystem {
        validateElementType(elem)
        includedElements.add(elem.id)
        return elem
    }

    override fun include(vararg elements: C4Element) {
        elements.forEach { elem ->
            validateElementType(elem)
            includedElements.add(elem.id)
        }
    }

    override fun exclude(vararg elements: C4Element) {
        elements.forEach { elem ->
            excludedElements.add(elem.id)
        }
    }

    override fun title(text: String) {
        // Future: store title annotation
    }

    override fun note(text: String) {
        // Future: store note annotation
    }

    /**
     * Builds the SystemContextDiagram from the current state.
     *
     * @return The constructed diagram with filtered elements and relationships
     * @throws IllegalArgumentException if invalid element types are included
     */
    fun build(): SystemContextDiagram {
        val finalElements = includedElements - excludedElements

        // Validate all included elements are persons or systems
        val invalidElements =
            finalElements
                .filter { id ->
                    val elem = parentModel.elements.find { it.id == id }
                    elem != null && elem !is C4Person && elem !is C4SoftwareSystem
                }

        if (invalidElements.isNotEmpty()) {
            throw IllegalArgumentException(
                "System Context Diagram can only contain persons and software systems, " +
                    "but found invalid element IDs: $invalidElements",
            )
        }

        // Filter relationships to only include those between included elements
        val filteredRelationships =
            parentModel.relationships
                .filter { rel ->
                    rel.source in finalElements && rel.target in finalElements
                }
                .map { it.id }

        return SystemContextDiagram(
            id = C4Ids.generateId(),
            name = "",
            // Set by caller via copy()
            description = description,
            elements = finalElements.toList(),
            relationships = filteredRelationships,
        )
    }

    private fun validateElementType(elem: C4Element) {
        if (elem !is C4Person && elem !is C4SoftwareSystem) {
            throw IllegalArgumentException(
                "System Context Diagram can only contain persons and software systems, " +
                    "but received: ${elem::class.simpleName}",
            )
        }
    }
}
