package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A C4 relationship element representing communication between C4 elements.
 *
 * @property id Unique stable identifier
 * @property source ID of the source element
 * @property target ID of the target element
 * @property label Label / description of the relationship / communication
 * @property technology Optional technology specification (e.g., "HTTPS", "REST API")
 * @property bidirectional True if the relationship is bidirectional
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4Relationship(
    override val id: ElementId,
    val source: ElementId,
    val target: ElementId,
    val label: String,
    val technology: String? = null,
    val bidirectional: Boolean = false,
    override val description: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element {
    override val name: String = label
}
