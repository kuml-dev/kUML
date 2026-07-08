package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A first-class database index, embedded in the owning [ErmEntity].
 *
 * [attributeIds] order is significant for composite indexes (leftmost-prefix
 * matching semantics of most SQL dialects).
 *
 * V3.4.1
 */
@Serializable
data class ErmIndex(
    override val id: String,
    override val name: String?,
    val attributeIds: List<String>,
    val unique: Boolean = false,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement
