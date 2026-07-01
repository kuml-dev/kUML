package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.ElementId
import dev.kuml.core.dsl.KumlDsl

/**
 * Scope for building a Container Diagram.
 *
 * Container Diagrams decompose a single software system to show its containers
 * (web app, API, database, message queue) and their relationships.
 * External systems can optionally be shown.
 */
@KumlDsl
interface ContainerDiagramBuilder {
    var system: C4SoftwareSystem?

    var description: String?

    var showExternalSystems: Boolean

    /**
     * If true (default), any [C4Person] that is related to the target system or any of its
     * containers (either as source or target of a relationship) is automatically included in
     * the diagram. Mirrors the Structurizr behaviour for Container Views, where the typical
     * picture is "users + the system's containers + adjacent external systems".
     */
    var showRelatedPersons: Boolean

    var showRelationships: Boolean

    fun exclude(vararg containers: C4Container)

    fun title(text: String)

    fun note(text: String)
}

/**
 * Implementation of ContainerDiagramBuilder.
 *
 * Automatically includes all containers of the target system and optionally
 * includes external systems that communicate with these containers.
 */
@KumlDsl
class ContainerDiagramBuilderImpl(
    private val parentModel: C4Model,
) : ContainerDiagramBuilder {
    override var system: C4SoftwareSystem? = null
    override var description: String? = null
    override var showExternalSystems: Boolean = true
    override var showRelatedPersons: Boolean = true
    override var showRelationships: Boolean = true

    private val excludedContainers = mutableSetOf<ElementId>()

    override fun exclude(vararg containers: C4Container) {
        containers.forEach { excludedContainers.add(it.id) }
    }

    override fun title(text: String) {
        // Future: store title annotation
    }

    override fun note(text: String) {
        // Future: store note annotation
    }

    /**
     * Builds the ContainerDiagram from the current state.
     *
     * @return The constructed diagram with filtered elements and relationships
     * @throws IllegalArgumentException if system is not set
     */
    fun build(): ContainerDiagram {
        val targetSystem =
            system
                ?: throw IllegalArgumentException("Container diagram requires a system to be set")

        // 1. Sammle alle Container dieses Systems
        val systemContainers =
            parentModel.elements
                .filterIsInstance<C4Container>()
                .filter { container ->
                    val isInSystem = container.system == targetSystem.id
                    val notExcluded = container.id !in excludedContainers
                    isInSystem && notExcluded
                }.map { it.id }

        // 2. Sammle externe Systeme (falls showExternalSystems = true)
        val externalSystems = findExternalSystems(targetSystem.id, systemContainers)

        // 3. Sammle verwandte Personen (falls showRelatedPersons = true)
        val relatedPersons = findRelatedPersons(targetSystem.id, systemContainers)

        // 4. Kombiniere alle Elemente (System + Container + externe Systeme + Personen)
        val allElements =
            listOf(targetSystem.id)
                .plus(systemContainers)
                .plus(externalSystems)
                .plus(relatedPersons)
                .distinct()

        // 5. Filtere Relationships
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

        return ContainerDiagram(
            id = C4Ids.generateId(),
            name = "",
            // Set by caller via copy()
            description = description,
            system = targetSystem.id,
            elements = allElements,
            relationships = filteredRelationships,
        )
    }

    private fun findExternalSystems(
        systemId: ElementId,
        systemContainers: List<ElementId>,
    ): List<ElementId> {
        if (!showExternalSystems) {
            return emptyList()
        }

        val relatedIds = mutableSetOf<ElementId>()

        for (rel in parentModel.relationships) {
            // From this system to other systems
            val fromThisSystem = (rel.source == systemId) || (rel.source in systemContainers)
            val targetIsSoftwareSystem =
                parentModel.elements
                    .find { it.id == rel.target } is C4SoftwareSystem
            val toOtherSystem = targetIsSoftwareSystem && (rel.target != systemId)

            if (fromThisSystem && toOtherSystem) {
                relatedIds.add(rel.target)
            }

            // From other systems to this system
            val sourceIsSoftwareSystem =
                parentModel.elements
                    .find { it.id == rel.source } is C4SoftwareSystem
            val fromOtherSystem = sourceIsSoftwareSystem && (rel.source != systemId)
            val toThisSystem = (rel.target == systemId) || (rel.target in systemContainers)

            if (fromOtherSystem && toThisSystem) {
                relatedIds.add(rel.source)
            }
        }

        return relatedIds.toList()
    }

    /**
     * Sammelt alle [C4Person]-Elemente, die in einer Beziehung zum Zielsystem oder einem
     * seiner Container stehen (egal in welche Richtung). Liefert eine leere Liste, wenn
     * [showRelatedPersons] auf `false` steht.
     */
    private fun findRelatedPersons(
        systemId: ElementId,
        systemContainers: List<ElementId>,
    ): List<ElementId> {
        if (!showRelatedPersons) {
            return emptyList()
        }

        val relatedIds = mutableSetOf<ElementId>()
        val systemBoundaryIds = systemContainers.toSet() + systemId

        for (rel in parentModel.relationships) {
            val sourceElement = parentModel.elements.find { it.id == rel.source }
            val targetElement = parentModel.elements.find { it.id == rel.target }

            // Person → System/Container
            if (sourceElement is C4Person && rel.target in systemBoundaryIds) {
                relatedIds.add(rel.source)
            }

            // System/Container → Person
            if (targetElement is C4Person && rel.source in systemBoundaryIds) {
                relatedIds.add(rel.target)
            }
        }

        return relatedIds.toList()
    }
}
