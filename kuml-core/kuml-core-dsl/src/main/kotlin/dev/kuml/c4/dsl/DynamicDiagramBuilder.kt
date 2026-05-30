package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Interaction
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.c4.model.ElementId
import dev.kuml.core.dsl.KumlDsl

/**
 * Scope for building a Dynamic Diagram.
 *
 * Dynamic Diagrams show how elements interact over time using sequence-like notation.
 * Interactions are ordered by the sequence in which they are defined.
 */
@KumlDsl
interface DynamicDiagramBuilder {
    var description: String?

    /**
     * Adds a request interaction from source to target.
     *
     * @param description The interaction description or message
     * @param from The source element
     * @param to The target element
     * @param technology Optional technology used (e.g., "REST/JSON", "HTTPS")
     */
    fun interaction(
        description: String,
        from: C4Element,
        to: C4Element,
        technology: String? = null,
    )

    /**
     * Adds a response interaction from source to target.
     *
     * @param description The interaction description or message
     * @param from The source element
     * @param to The target element
     * @param technology Optional technology used (e.g., "REST/JSON", "HTTPS")
     */
    fun response(
        description: String,
        from: C4Element,
        to: C4Element,
        technology: String? = null,
    )

    /**
     * Sets the title for the diagram.
     *
     * @param text The title text
     */
    fun title(text: String)

    /**
     * Adds a note annotation to the diagram.
     *
     * @param text The note text
     */
    fun note(text: String)
}

/**
 * Implementation of DynamicDiagramBuilder.
 *
 * Tracks interactions in order, collects participating elements, and filters relationships.
 */
@KumlDsl
class DynamicDiagramBuilderImpl(
    private val parentModel: C4Model,
) : DynamicDiagramBuilder {
    override var description: String? = null

    private val interactions = mutableListOf<C4Interaction>()
    private val elementIds = mutableSetOf<ElementId>()
    private var sequenceCounter = 0

    override fun interaction(
        description: String,
        from: C4Element,
        to: C4Element,
        technology: String?,
    ) {
        sequenceCounter++

        interactions.add(
            C4Interaction(
                id = C4Ids.generateId(),
                source = from.id,
                target = to.id,
                description = description,
                sequence = sequenceCounter,
                technology = technology,
                response = false,
            ),
        )

        elementIds.add(from.id)
        elementIds.add(to.id)
    }

    override fun response(
        description: String,
        from: C4Element,
        to: C4Element,
        technology: String?,
    ) {
        sequenceCounter++

        interactions.add(
            C4Interaction(
                id = C4Ids.generateId(),
                source = from.id,
                target = to.id,
                description = description,
                sequence = sequenceCounter,
                technology = technology,
                response = true,
            ),
        )

        elementIds.add(from.id)
        elementIds.add(to.id)
    }

    override fun title(text: String) {
        // Future: store title annotation
    }

    override fun note(text: String) {
        // Future: store note annotation
    }

    /**
     * Builds the DynamicDiagram from the current state.
     *
     * @return The constructed diagram with interactions and filtered relationships
     */
    fun build(): DynamicDiagram {
        // Filter relationships to only include those between included elements
        val filteredRelationships =
            parentModel.relationships
                .filter { rel ->
                    rel.source in elementIds && rel.target in elementIds
                }
                .map { it.id }

        return DynamicDiagram(
            id = C4Ids.generateId(),
            name = "",
            // Set by caller via copy()
            description = description,
            interactions = interactions,
            elements = elementIds.toList(),
            relationships = filteredRelationships,
        )
    }
}
