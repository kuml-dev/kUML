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

/**
 * Component Diagram — Level 3 of C4.
 *
 * Decomposes a single container to show its components (classes, services, modules)
 * and their relationships. Can optionally show external containers that communicate with these components.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property container The container ID that this diagram decomposes
 * @property elements List of component and (optionally) external container element IDs
 * @property relationships List of relationship IDs visible in this diagram
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class ComponentDiagram(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val container: ElementId,
    override val elements: List<ElementId> = emptyList(),
    override val relationships: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Diagram

/**
 * Deployment Diagram — Level 4 of C4.
 *
 * Shows how the software system is deployed across infrastructure nodes (servers, containers,
 * cloud services) and which containers run on which nodes. Supports hierarchical nesting
 * of deployment nodes.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property elements List of deployment node and container instance element IDs
 * @property relationships List of relationship IDs visible in this diagram
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class DeploymentDiagram(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    override val elements: List<ElementId> = emptyList(),
    override val relationships: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Diagram

/**
 * A single interaction in a Dynamic Diagram.
 *
 * Represents a message or call between two elements, ordered by sequence number.
 * Can be a request or a response.
 *
 * @property id Unique stable identifier
 * @property source The source element ID
 * @property target The target element ID
 * @property description The interaction description or message
 * @property sequence The order in which this interaction occurs (1, 2, 3...)
 * @property technology Optional technology used in the interaction (e.g., "REST/JSON")
 * @property response True if this is a response message, false if it is a request
 */
@Serializable
data class C4Interaction(
    val id: ElementId,
    val source: ElementId,
    val target: ElementId,
    val description: String,
    val sequence: Int,
    val technology: String? = null,
    val response: Boolean = false,
)

/**
 * Dynamic Diagram — Level 4 of C4.
 *
 * Shows how elements interact over time, using sequence-like diagrams.
 * Captures dynamic behavior and message flows between system components.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property interactions List of C4Interaction objects, ordered by sequence
 * @property elements List of element IDs involved in this diagram
 * @property relationships List of relationship IDs visible in this diagram
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class DynamicDiagram(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val interactions: List<C4Interaction> = emptyList(),
    override val elements: List<ElementId> = emptyList(),
    override val relationships: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Diagram
