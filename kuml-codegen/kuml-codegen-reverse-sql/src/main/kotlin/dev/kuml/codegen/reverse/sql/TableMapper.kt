package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmIndex
import dev.kuml.erm.model.ReferentialAction
import net.sf.jsqlparser.statement.create.table.CreateTable

/**
 * Mutable in-progress [ErmEntity] used while the two-pass reverse pipeline
 * resolves cross-entity foreign keys (V3.4.9). [attributes], [indexes] and
 * [checks] are mutated in place by [ConstraintResolver]; [toErmEntity]
 * freezes the final immutable snapshot.
 */
internal class MutableErmEntity(
    val id: String,
    val ix: Int,
    var name: String?,
) {
    val attributes: MutableList<ErmAttribute> = mutableListOf()
    var weak: Boolean = false
    val indexes: MutableList<ErmIndex> = mutableListOf()
    val checks: MutableList<ErmCheckConstraint> = mutableListOf()

    private var attrCounter = 0

    fun nextAttrId(): String = "attr_${ix}_${attrCounter++}"

    fun toErmEntity(): ErmEntity =
        ErmEntity(
            id = id,
            name = name,
            attributes = attributes.toList(),
            weak = weak,
            indexes = indexes.toList(),
            checks = checks.toList(),
        )
}

/**
 * A not-yet-resolved foreign key — collected during pass 1 (column/table-level
 * parsing) and resolved during pass 2 ([ConstraintResolver.resolveForeignKeys])
 * once every `CREATE TABLE` has been seen, so forward references (table A
 * references table B declared later in the file) and `ALTER TABLE ADD
 * CONSTRAINT` references resolve correctly.
 */
internal data class PendingForeignKey(
    val fromEntityId: String,
    val fromAttributeId: String,
    val targetTableName: String,
    val targetColumnName: String?,
    val onDelete: ReferentialAction,
    val onUpdate: ReferentialAction,
    val sourceLabel: String,
)

/**
 * Maps a single `CREATE TABLE` statement to a [MutableErmEntity] — columns via
 * [ColumnMapper], table-level constraints (`PRIMARY KEY`/`UNIQUE`/`FOREIGN
 * KEY`/`CHECK`) via [ConstraintResolver.applyIndexConstraint]. Inline foreign
 * keys and inline `CHECK` expressions surfaced by [ColumnMapper] are folded in
 * directly since they never need cross-table resolution to be *recorded* (only
 * the foreign key's *target* needs pass-2 resolution).
 */
internal object TableMapper {
    fun map(
        ct: CreateTable,
        entityId: String,
        entityIx: Int,
        diagnostics: MutableList<ReverseDiagnostic>,
        pendingForeignKeys: MutableList<PendingForeignKey>,
        fileHint: String?,
    ): MutableErmEntity {
        val name = SqlIdentifiers.fold(ct.table.name)
        val entity = MutableErmEntity(id = entityId, ix = entityIx, name = name)

        for (cd in ct.columnDefinitions.orEmpty()) {
            val attrId = entity.nextAttrId()
            val mapped = ColumnMapper.map(cd, attrId, diagnostics, fileHint)
            entity.attributes += mapped.attribute
            mapped.inlineForeignKey?.let { ref ->
                pendingForeignKeys +=
                    PendingForeignKey(
                        fromEntityId = entity.id,
                        fromAttributeId = attrId,
                        targetTableName = ref.targetTableName,
                        targetColumnName = ref.targetColumnName,
                        onDelete = ref.onDelete,
                        onUpdate = ref.onUpdate,
                        sourceLabel = "$name.${mapped.attribute.name} (inline)",
                    )
            }
            mapped.checkExpression?.let { expr ->
                entity.checks +=
                    ErmCheckConstraint(
                        id = "check_${entity.ix}_${entity.checks.size}",
                        name = null,
                        expression = expr,
                    )
            }
        }

        for (idx in ct.indexes.orEmpty()) {
            ConstraintResolver.applyIndexConstraint(entity, idx, pendingForeignKeys, diagnostics, fileHint)
        }

        return entity
    }
}
