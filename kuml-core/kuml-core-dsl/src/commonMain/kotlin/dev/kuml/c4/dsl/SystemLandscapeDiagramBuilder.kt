package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ElementId
import dev.kuml.c4.model.SystemLandscapeDiagram
import dev.kuml.core.dsl.KumlDsl

/**
 * Scope for building a System Landscape Diagram.
 *
 * System Landscape Diagrams show all software systems and persons in the enterprise
 * along with their relationships. This is a high-level enterprise overview without
 * decomposing individual systems.
 */
@KumlDsl
interface SystemLandscapeDiagramBuilder {
    /** Layout steering hints (`layout { direction = Right }`); several blocks merge. */
    fun layout(block: C4LayoutBuilder.() -> Unit)

    var description: String?

    var includeAllSystems: Boolean

    var includeAllPersons: Boolean

    fun include(vararg elements: C4Element)

    fun exclude(vararg elements: C4Element)

    fun title(text: String)

    fun note(
        text: String,
        vararg anchors: C4Element,
        id: String? = null,
    )
}

/**
 * Implementation of SystemLandscapeDiagramBuilder.
 *
 * By default, includes all persons and systems in the model. Supports explicit
 * include/exclude for fine-grained control.
 */
@KumlDsl
class SystemLandscapeDiagramBuilderImpl(
    private val parentModel: C4Model,
    private val noteRegistry: C4NoteRegistry = C4NoteRegistry(),
) : SystemLandscapeDiagramBuilder {
    override var description: String? = null
    override var includeAllSystems: Boolean = true
    override var includeAllPersons: Boolean = true

    private val includedElements = mutableSetOf<ElementId>()
    private val excludedElements = mutableSetOf<ElementId>()

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

    private val layoutCollector =
        dev.kuml.core.model.layout
            .LayoutSpecCollector()

    override fun layout(block: C4LayoutBuilder.() -> Unit) {
        layoutCollector.add(C4LayoutBuilder().apply(block).build())
    }

    private val diagramNoteIds = mutableListOf<String>()

    override fun note(
        text: String,
        vararg anchors: C4Element,
        id: String?,
    ) {
        diagramNoteIds += noteRegistry.add(text = text, anchors = anchors.toList(), id = id)
    }

    /**
     * Builds the SystemLandscapeDiagram from the current state.
     *
     * @return The constructed diagram with filtered elements and relationships
     * @throws IllegalArgumentException if invalid element types are included
     */
    fun build(): SystemLandscapeDiagram {
        // Determine which elements to include
        val candidateElements = mutableSetOf<ElementId>()

        // 1. Add auto-included elements based on flags
        candidateElements.addAll(
            parentModel.elements
                .filter { elem ->
                    val isSystem = elem is C4SoftwareSystem
                    val isPerson = elem is C4Person
                    (isSystem && includeAllSystems) || (isPerson && includeAllPersons)
                }.map { it.id },
        )

        // 2. Add explicitly included elements (additive)
        candidateElements.addAll(includedElements)

        // 3. Apply exclusions
        val elementsToInclude = candidateElements - excludedElements

        // Validate all included elements are persons or systems
        val invalidElements =
            elementsToInclude
                .filter { id ->
                    val elem = parentModel.elements.find { it.id == id }
                    elem != null && elem !is C4Person && elem !is C4SoftwareSystem
                }

        if (invalidElements.isNotEmpty()) {
            throw IllegalArgumentException(
                "System Landscape Diagram can only contain persons and software systems, " +
                    "but found invalid element IDs: $invalidElements",
            )
        }

        // Filter relationships to only include those between included elements
        val filteredRelationships =
            parentModel.relationships
                .filter { rel ->
                    rel.source in elementsToInclude && rel.target in elementsToInclude
                }.map { it.id }

        return SystemLandscapeDiagram(
            id = C4Ids.generateId(),
            name = "",
            // Set by caller via copy()
            description = description,
            elements = elementsToInclude.toList(),
            relationships = filteredRelationships,
            noteIds = diagramNoteIds.toList(),
            layout = layoutCollector.result(),
        )
    }

    private fun validateElementType(elem: C4Element) {
        if (elem !is C4Person && elem !is C4SoftwareSystem) {
            throw IllegalArgumentException(
                "System Landscape Diagram can only contain persons and software systems, " +
                    "but received: ${elem::class.simpleName}",
            )
        }
    }
}
