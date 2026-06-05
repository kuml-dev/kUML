package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Root C4 model element containing all structural elements and relationships.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name of the model
 * @property description Optional description
 * @property elements All C4 elements (persons, systems, containers, components, deployment nodes)
 * @property relationships All C4 relationships between elements
 * @property diagrams All C4 diagrams (views of the model)
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4Model(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val elements: List<C4Element> = emptyList(),
    val relationships: List<C4Relationship> = emptyList(),
    val diagrams: List<C4Diagram> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element
