package dev.kuml.c4.model

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Sealed interface for all C4 diagram types.
 *
 * C4 diagrams are views of a C4 model that show specific elements and relationships.
 * Each diagram type filters the model to show a particular level of abstraction.
 */
@Serializable
sealed interface C4Diagram : KumlElement {
    val name: String
    val description: String?
    val elements: List<ElementId>
    val relationships: List<ElementId>
}

/**
 * System Context Diagram — Level 1 of C4.
 *
 * Shows the system in scope and its relationships to users and external systems.
 * Only persons and software systems are allowed.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property elements List of person and software system element IDs
 * @property relationships List of relationship IDs visible in this diagram
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class SystemContextDiagram(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    override val elements: List<ElementId> = emptyList(),
    override val relationships: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Diagram

/**
 * Container Diagram — Level 2 of C4.
 *
 * Decomposes a single software system to show its containers (web app, API, database, message queue)
 * and their relationships. Can optionally show external systems that communicate with these containers.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property system The system ID that this diagram decomposes
 * @property elements List of container and (optionally) external system element IDs
 * @property relationships List of relationship IDs visible in this diagram
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class ContainerDiagram(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val system: ElementId,
    override val elements: List<ElementId> = emptyList(),
    override val relationships: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Diagram

/**
 * System Landscape Diagram — Enterprise overview.
 *
 * Shows all software systems and persons in the enterprise, along with their relationships.
 * This is a high-level view showing how different systems and users interact without
 * decomposing individual systems.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property elements List of person and software system element IDs
 * @property relationships List of relationship IDs visible in this diagram
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class SystemLandscapeDiagram(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    override val elements: List<ElementId> = emptyList(),
    override val relationships: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Diagram
