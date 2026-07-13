package dev.kuml.erm.dsl

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmIndex
import dev.kuml.erm.model.ErmMetadataKeys
import dev.kuml.erm.model.ReferentialAction

/**
 * Builder scoped to a single entity — attributes, indexes and check
 * constraints declared here are bound to that entity.
 *
 * Auto-ids are deterministic (`attr_<entityIx>_<n>`, `idx_<entityIx>_<n>`,
 * `check_<entityIx>_<n>`) — never UUIDs — so snapshot/diff tests stay stable.
 *
 * V3.4.1
 */
@ErmDsl
class EntityBuilder internal constructor(
    private val entityId: String,
    private val entityIx: Int,
    private val model: ErmModelBuilder,
) {
    private val attributes = mutableListOf<ErmAttribute>()
    private val indexes = mutableListOf<ErmIndex>()
    private val checks = mutableListOf<ErmCheckConstraint>()
    private var metadata: Map<String, KumlMetaValue> = emptyMap()

    /** Declares a column on this entity. */
    fun attribute(
        name: String,
        type: ErmDataType,
        primaryKey: Boolean = false,
        nullable: Boolean = true,
        unique: Boolean = false,
        default: String? = null,
        autoIncrement: Boolean = false,
        foreignKey: ErmForeignKey? = null,
    ): String {
        val id = "attr_${entityIx}_${attributes.size}"
        attributes +=
            ErmAttribute(
                id = id,
                name = name,
                type = type,
                primaryKey = primaryKey,
                nullable = nullable,
                unique = unique,
                default = default,
                foreignKey = foreignKey,
                autoIncrement = autoIncrement,
            )
        return id
    }

    /** Convenience: a not-null primary-key column, `UUID` by default. */
    fun id(
        name: String = "id",
        type: ErmDataType = ErmDataType.Uuid,
    ): String =
        attribute(
            name = name,
            type = type,
            primaryKey = true,
            nullable = false,
        )

    /**
     * Convenience: a foreign-key column referencing another entity's primary
     * key. The column type is inferred from the target's (single-column)
     * primary key; falls back to [ErmDataType.Uuid] if the target has no
     * single-column primary key (e.g. it hasn't been declared yet, or has a
     * composite key).
     */
    fun foreignKey(
        name: String,
        references: String,
        onDelete: ReferentialAction = ReferentialAction.NO_ACTION,
        nullable: Boolean = true,
    ): String {
        val targetPk = model.entityById(references)?.primaryKey?.singleOrNull()
        val type = targetPk?.type ?: ErmDataType.Uuid
        return attribute(
            name = name,
            type = type,
            nullable = nullable,
            foreignKey = ErmForeignKey(targetEntityId = references, onDelete = onDelete),
        )
    }

    /** Declares an index over one or more of this entity's columns (by declared name). */
    fun index(
        vararg attributeNames: String,
        unique: Boolean = false,
        name: String? = null,
    ) {
        val attributeIds = attributeNames.mapNotNull { attrName -> attributes.firstOrNull { it.name == attrName }?.id }
        indexes +=
            ErmIndex(
                id = "idx_${entityIx}_${indexes.size}",
                name = name,
                attributeIds = attributeIds,
                unique = unique,
            )
    }

    /** Declares a `CHECK` constraint with a raw, dialect-neutral SQL boolean [expression]. */
    fun check(
        expression: String,
        name: String? = null,
    ) {
        checks +=
            ErmCheckConstraint(
                id = "check_${entityIx}_${checks.size}",
                name = name,
                expression = expression,
            )
    }

    /**
     * Marks this entity as a TimescaleDB hypertable (Postgres-only — honored by
     * `ErmSqlEmitter.renderHypertables`; every other SQL dialect and the Exposed
     * emitter ignore the marker except for an explanatory comment). [timeColumn]
     * must name an attribute already (or later) declared on this entity — that is
     * validated at emission time, not here, since attribute declaration order
     * relative to [hypertable] within the builder block is not constrained.
     */
    fun hypertable(
        timeColumn: String,
        chunkInterval: String? = null,
    ) {
        val entries =
            buildMap {
                put(ErmMetadataKeys.HT_TIME_COLUMN, KumlMetaValue.Text(timeColumn))
                chunkInterval?.let { put(ErmMetadataKeys.HT_CHUNK_INTERVAL, KumlMetaValue.Text(it)) }
            }
        metadata = metadata + (ErmMetadataKeys.HYPERTABLE to KumlMetaValue.Entries(entries))
    }

    /**
     * Overrides the mechanically-derived Kotlin `object` name that
     * `ErmExposedEmitter` would otherwise derive via `PascalCase(entity.name)`
     * for this entity's generated Exposed `Table` object. Purely a naming
     * override — the physical table name (`Table("...")` string literal,
     * still `entity.name`) is unaffected. [name] is validated as a Kotlin
     * identifier at emission time, not here (mirrors [hypertable]'s
     * time-column validation deferral).
     */
    fun kotlinObjectName(name: String) {
        metadata = metadata + (ErmMetadataKeys.KOTLIN_OBJECT_NAME to KumlMetaValue.Text(name))
    }

    internal fun build(
        name: String,
        weak: Boolean,
    ): ErmEntity =
        ErmEntity(
            id = entityId,
            name = name,
            attributes = attributes.toList(),
            weak = weak,
            indexes = indexes.toList(),
            checks = checks.toList(),
            metadata = metadata,
        )
}
