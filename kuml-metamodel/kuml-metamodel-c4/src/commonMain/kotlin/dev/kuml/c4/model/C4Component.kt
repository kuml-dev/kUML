package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A C4 component element representing a logical structural block within a container.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property technology Optional technology specification
 * @property container Optional ID of the parent container
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4Component(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val technology: String? = null,
    val container: ElementId? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element
