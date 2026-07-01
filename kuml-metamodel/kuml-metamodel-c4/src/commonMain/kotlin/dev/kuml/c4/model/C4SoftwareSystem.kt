package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A C4 software system element representing a logical boundary of responsibility.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property external True if this system is external to the context
 * @property location Optional location information
 * @property containers List of container element IDs that belong to this system
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4SoftwareSystem(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val external: Boolean = false,
    val location: String? = null,
    val containers: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element
