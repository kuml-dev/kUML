package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A column of an [ErmEntity].
 *
 * Carries the full logical-column vocabulary needed by a later SQL-dialect
 * code generator (V3.4.7): primary key, nullability, uniqueness, a raw
 * dialect-neutral [default] expression, an optional [foreignKey], and
 * [autoIncrement].
 *
 * V3.4.1
 */
@Serializable
data class ErmAttribute(
    override val id: String,
    override val name: String?,
    val type: ErmDataType,
    val primaryKey: Boolean = false,
    val nullable: Boolean = true,
    val unique: Boolean = false,
    val default: String? = null,
    val foreignKey: ErmForeignKey? = null,
    val autoIncrement: Boolean = false,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement
