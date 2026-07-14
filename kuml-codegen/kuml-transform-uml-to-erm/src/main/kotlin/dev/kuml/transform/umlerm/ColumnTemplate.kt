package dev.kuml.transform.umlerm

import dev.kuml.erm.model.ErmDataType

/**
 * Intermediate representation of a mapped column, before an [ErmAttribute] id
 * has been allocated for a specific target [MutableErmEntity] — the same
 * template is copied into several entities for [InheritanceStrategy.SINGLE_TABLE]
 * (nullable copy into the root) and [InheritanceStrategy.TABLE_PER_CLASS]
 * (copy into every concrete descendant), each time with a fresh, entity-scoped id.
 *
 * V3.4.6
 */
internal data class ColumnTemplate(
    val name: String,
    val type: ErmDataType,
    val primaryKey: Boolean,
    val nullable: Boolean,
    val unique: Boolean,
    val default: String?,
    val autoIncrement: Boolean,
    val sourceAttrId: String,
    val checkExpression: String? = null,
    /** `«Column».fkEntity` — target UML class name for a column-level FK override, if present. */
    val fkEntityName: String? = null,
    /** `«Column».fkAttribute` — target ERM column name, if present (null = target's primary key). */
    val fkAttributeName: String? = null,
) {
    /** Returns a copy forced to nullable and non-primary-key — used for SINGLE_TABLE descendant merges. */
    fun asNullableNonKey(): ColumnTemplate = copy(nullable = true, primaryKey = false, autoIncrement = false)
}
