package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ElementId
import dev.kuml.core.dsl.KumlDsl

/**
 * Scope for building a Component Diagram.
 *
 * Component Diagrams decompose a single container to show its components
 * (classes, services, modules) and their relationships.
 * External containers can optionally be shown.
 */
@KumlDsl
interface ComponentDiagramBuilder {
    var container: C4Container?

    var description: String?

    var showExternalReferences: Boolean

    var showRelationships: Boolean

    fun exclude(vararg components: C4Component)

    fun title(text: String)

    fun note(text: String)
}

/**
 * Implementation of ComponentDiagramBuilder.
 *
 * Automatically includes all components of the target container and optionally
 * includes external containers that communicate with these components.
 */
@KumlDsl
class ComponentDiagramBuilderImpl(
    private val parentModel: C4Model,
) : ComponentDiagramBuilder {
    override var container: C4Container? = null
    override var description: String? = null
    override var showExternalReferences: Boolean = true
    override var showRelationships: Boolean = true

    private val excludedComponents = mutableSetOf<ElementId>()

    override fun exclude(vararg components: C4Component) {
        components.forEach { excludedComponents.add(it.id) }
    }

    override fun title(text: String) {
        // Future: store title annotation
    }

    override fun note(text: String) {
        // Future: store note annotation
    }

    /**
     * Builds the ComponentDiagram from the current state.
     *
     * @return The constructed diagram with filtered elements and relationships
     * @throws IllegalArgumentException if container is not set
     */
    fun build(): ComponentDiagram {
        val targetContainer =
            container
                ?: throw IllegalArgumentException("Component diagram requires a container to be set")

        // 1. Sammle alle Komponenten dieses Containers
        val containerComponents =
            parentModel.elements
                .filterIsInstance<C4Component>()
                .filter { component ->
                    val isInContainer = component.container == targetContainer.id
                    val notExcluded = component.id !in excludedComponents
                    isInContainer && notExcluded
                }.map { it.id }

        // 2. Sammle externe Container (falls showExternalReferences = true)
        val externalContainers = findExternalContainers(targetContainer.id, containerComponents)

        // 3. Kombiniere alle Elemente (Container + seine Komponenten + externe Container)
        val allElements =
            listOf(targetContainer.id)
                .plus(containerComponents)
                .plus(externalContainers)
                .distinct()

        // 4. Filtere Relationships
        val filteredRelationships =
            if (showRelationships) {
                parentModel.relationships
                    .filter { rel ->
                        val fromIncluded = rel.source in allElements
                        val toIncluded = rel.target in allElements
                        fromIncluded && toIncluded
                    }.map { it.id }
            } else {
                emptyList()
            }

        return ComponentDiagram(
            id = C4Ids.generateId(),
            name = "",
            // Set by caller via copy()
            description = description,
            container = targetContainer.id,
            elements = allElements,
            relationships = filteredRelationships,
        )
    }

    private fun findExternalContainers(
        containerId: ElementId,
        containerComponents: List<ElementId>,
    ): List<ElementId> {
        if (!showExternalReferences) {
            return emptyList()
        }

        val relatedIds = mutableSetOf<ElementId>()

        for (rel in parentModel.relationships) {
            // From this container or its components to other containers
            val fromThisContainer =
                (rel.source == containerId) || (rel.source in containerComponents)
            val targetIsContainer =
                parentModel.elements
                    .filterIsInstance<C4Container>()
                    .any { it.id == rel.target }
            val toOtherContainer = targetIsContainer && (rel.target != containerId)

            if (fromThisContainer && toOtherContainer) {
                relatedIds.add(rel.target)
            }

            // From other containers to this container or its components
            val sourceIsContainer =
                parentModel.elements
                    .filterIsInstance<C4Container>()
                    .any { it.id == rel.source }
            val fromOtherContainer = sourceIsContainer && (rel.source != containerId)
            val toThisContainer = (rel.target == containerId) || (rel.target in containerComponents)

            if (fromOtherContainer && toThisContainer) {
                relatedIds.add(rel.source)
            }
        }

        return relatedIds.toList()
    }
}
