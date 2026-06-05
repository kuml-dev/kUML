package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A C4 person element representing a user or actor.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property external True if this person is external to the system
 * @property location Optional location information
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4Person(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val external: Boolean = false,
    val location: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element
