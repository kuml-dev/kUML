package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmIndex
import dev.kuml.erm.model.ErmView
import dev.kuml.erm.model.ReferentialAction
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.alter.AlterOperation
import net.sf.jsqlparser.statement.create.index.CreateIndex
import net.sf.jsqlparser.statement.create.table.CheckConstraint
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex
import net.sf.jsqlparser.statement.create.table.Index
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.util.TablesNamesFinder
import net.sf.jsqlparser.statement.ReferentialAction as JSqlReferentialAction
import net.sf.jsqlparser.statement.Statement as JSqlStatement

/**
 * Pass-2 constraint resolution for the SQL→ERM reverse engine (V3.4.9):
 * table-level and `ALTER TABLE` constraints (`PRIMARY KEY`/`UNIQUE`/`FOREIGN
 * KEY`/`CHECK`), `CREATE INDEX`, `CREATE VIEW`, and the final foreign-key →
 * [ErmForeignKey] resolution against the table-name → entity-id index built
 * once every `CREATE TABLE` has been mapped (so forward references and
 * `ALTER TABLE ADD CONSTRAINT` — the common Flyway/pg_dump shape for foreign
 * keys — resolve correctly; stolperfalle #4 of the plan).
 */
internal object ConstraintResolver {
    // ── Table-level / ALTER-level constraint dispatch (Index subtypes) ─────────

    /**
     * Applies one table-level constraint (`ct.getIndexes()` entry, or the
     * `ae.getIndex()` payload of an `ALTER TABLE ADD CONSTRAINT`/`ADD PRIMARY
     * KEY`/`ADD UNIQUE` expression) to [entity]. Both call sites share this
     * dispatch because JSqlParser represents them with the exact same [Index]
     * subtypes ([ForeignKeyIndex], [CheckConstraint], or a plain named
     * `PRIMARY KEY`/`UNIQUE` constraint).
     */
    fun applyIndexConstraint(
        entity: MutableErmEntity,
        idx: Index,
        pendingForeignKeys: MutableList<PendingForeignKey>,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
    ) {
        when (idx) {
            is ForeignKeyIndex -> {
                val cols = columnNames(idx)
                if (cols.isEmpty()) return
                if (cols.size > 1) {
                    diagnostics +=
                        ReverseDiagnostic(
                            ReverseDiagnostic.Severity.WARN,
                            "REV-SQL-011",
                            "Composite foreign key on ${entity.name}(${cols.joinToString()}) is only " +
                                "partially mapped — kUML's ErmForeignKey is single-column, the foreign " +
                                "key is attached to the first column only.",
                            file = fileHint,
                        )
                }
                val fromAttr = entity.attributes.firstOrNull { it.name == cols.first() } ?: return
                val targetTable = SqlIdentifiers.fold(idx.table.name)
                val targetCol = idx.referencedColumnNames?.firstOrNull()?.let { SqlIdentifiers.fold(it) }
                pendingForeignKeys +=
                    PendingForeignKey(
                        fromEntityId = entity.id,
                        fromAttributeId = fromAttr.id,
                        targetTableName = targetTable,
                        targetColumnName = targetCol,
                        onDelete = mapAction(idx.getReferentialAction(JSqlReferentialAction.Type.DELETE)?.action),
                        onUpdate = mapAction(idx.getReferentialAction(JSqlReferentialAction.Type.UPDATE)?.action),
                        sourceLabel = idx.name?.let { SqlIdentifiers.fold(it) } ?: "${entity.name}.${cols.first()}",
                    )
            }
            is CheckConstraint -> {
                entity.checks +=
                    ErmCheckConstraint(
                        id = "check_${entity.ix}_${entity.checks.size}",
                        name = idx.name?.let { SqlIdentifiers.fold(it) },
                        expression = idx.expression.toString(),
                    )
            }
            else -> {
                val cols = columnNames(idx)
                when (idx.type?.uppercase()) {
                    "PRIMARY KEY" -> applyPrimaryKey(entity, cols)
                    "UNIQUE" -> applyUnique(entity, cols, idx.name?.let { SqlIdentifiers.fold(it) })
                    else ->
                        diagnostics +=
                            ReverseDiagnostic(
                                ReverseDiagnostic.Severity.INFO,
                                "REV-SQL-014",
                                "Table constraint of type '${idx.type}' on '${entity.name}' is not supported — skipped.",
                                file = fileHint,
                            )
                }
            }
        }
    }

    fun applyPrimaryKey(
        entity: MutableErmEntity,
        colNames: List<String>,
    ) {
        for (name in colNames) {
            val ix = entity.attributes.indexOfFirst { it.name == name }
            if (ix >= 0) entity.attributes[ix] = entity.attributes[ix].copy(primaryKey = true, nullable = false)
        }
    }

    fun applyUnique(
        entity: MutableErmEntity,
        colNames: List<String>,
        name: String?,
    ) {
        if (colNames.size == 1) {
            val ix = entity.attributes.indexOfFirst { it.name == colNames[0] }
            if (ix >= 0) entity.attributes[ix] = entity.attributes[ix].copy(unique = true)
        } else if (colNames.size > 1) {
            val attrIds = colNames.mapNotNull { n -> entity.attributes.firstOrNull { it.name == n }?.id }
            entity.indexes +=
                ErmIndex(id = "idx_${entity.ix}_${entity.indexes.size}", name = name, attributeIds = attrIds, unique = true)
        }
    }

    private fun columnNames(idx: Index): List<String> = (idx.columns?.map { it.columnName } ?: emptyList()).map { SqlIdentifiers.fold(it) }

    private fun mapAction(action: JSqlReferentialAction.Action?): ReferentialAction =
        when (action) {
            JSqlReferentialAction.Action.CASCADE -> ReferentialAction.CASCADE
            JSqlReferentialAction.Action.RESTRICT -> ReferentialAction.RESTRICT
            JSqlReferentialAction.Action.SET_NULL -> ReferentialAction.SET_NULL
            JSqlReferentialAction.Action.SET_DEFAULT -> ReferentialAction.SET_DEFAULT
            JSqlReferentialAction.Action.NO_ACTION, null -> ReferentialAction.NO_ACTION
        }

    // ── ALTER TABLE ──────────────────────────────────────────────────────────

    fun applyAlter(
        alter: Alter,
        entities: Map<String, MutableErmEntity>,
        nameIndex: Map<String, String>,
        pendingForeignKeys: MutableList<PendingForeignKey>,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
    ) {
        val tableName = SqlIdentifiers.fold(alter.table.name)
        val entity = nameIndex[tableName]?.let { entities[it] }
        if (entity == null) {
            diagnostics +=
                ReverseDiagnostic(
                    ReverseDiagnostic.Severity.WARN,
                    "REV-SQL-002",
                    "ALTER TABLE references unknown table '$tableName' — statement skipped.",
                    file = fileHint,
                )
            return
        }
        for (ae in alter.alterExpressions.orEmpty()) {
            if (ae.operation != AlterOperation.ADD) {
                diagnostics +=
                    ReverseDiagnostic(
                        ReverseDiagnostic.Severity.INFO,
                        "REV-SQL-014",
                        "ALTER TABLE operation '${ae.operation}' on '$tableName' is not applied " +
                            "(only ADD CONSTRAINT/ADD COLUMN/ADD PRIMARY KEY/ADD UNIQUE are mapped).",
                        file = fileHint,
                    )
                continue
            }
            when {
                ae.pkColumns != null -> applyPrimaryKey(entity, ae.pkColumns.map { SqlIdentifiers.fold(it) })
                ae.ukColumns != null ->
                    applyUnique(
                        entity,
                        ae.ukColumns.map { SqlIdentifiers.fold(it) },
                        ae.ukName?.let { SqlIdentifiers.fold(it) },
                    )
                ae.index != null -> applyIndexConstraint(entity, ae.index, pendingForeignKeys, diagnostics, fileHint)
                ae.colDataTypeList != null -> {
                    for (cdt in ae.colDataTypeList) {
                        val attrId = entity.nextAttrId()
                        val mapped = ColumnMapper.map(cdt, attrId, diagnostics, fileHint)
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
                                    sourceLabel = "${entity.name}.${mapped.attribute.name} (ALTER ADD COLUMN)",
                                )
                        }
                        mapped.checkExpression?.let { expr ->
                            entity.checks +=
                                ErmCheckConstraint(id = "check_${entity.ix}_${entity.checks.size}", name = null, expression = expr)
                        }
                    }
                }
                else ->
                    diagnostics +=
                        ReverseDiagnostic(
                            ReverseDiagnostic.Severity.INFO,
                            "REV-SQL-014",
                            "ALTER TABLE ADD ... on '$tableName' was not recognized — skipped.",
                            file = fileHint,
                        )
            }
        }
    }

    // ── Foreign key resolution (pass 2) ─────────────────────────────────────

    fun resolveForeignKeys(
        pending: List<PendingForeignKey>,
        entities: Map<String, MutableErmEntity>,
        nameIndex: Map<String, String>,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
    ) {
        for (p in pending) {
            val sourceEntity = entities[p.fromEntityId] ?: continue
            val targetEntityId = nameIndex[p.targetTableName]
            if (targetEntityId == null) {
                diagnostics +=
                    ReverseDiagnostic(
                        ReverseDiagnostic.Severity.WARN,
                        "REV-SQL-012",
                        "Foreign key '${p.sourceLabel}' references unknown table '${p.targetTableName}' — relationship skipped.",
                        file = fileHint,
                    )
                continue
            }
            val targetEntity = entities.getValue(targetEntityId)
            val targetAttrId = resolveTargetAttributeId(p, targetEntity, diagnostics, fileHint)

            val fk =
                ErmForeignKey(
                    targetEntityId = targetEntityId,
                    targetAttributeId = targetAttrId,
                    onDelete = p.onDelete,
                    onUpdate = p.onUpdate,
                )
            val ix = sourceEntity.attributes.indexOfFirst { it.id == p.fromAttributeId }
            if (ix < 0) continue
            sourceEntity.attributes[ix] = sourceEntity.attributes[ix].copy(foreignKey = fk)
        }
    }

    private fun resolveTargetAttributeId(
        p: PendingForeignKey,
        targetEntity: MutableErmEntity,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
    ): String? {
        val targetColumnName = p.targetColumnName ?: return null
        val found = targetEntity.attributes.firstOrNull { it.name == targetColumnName }
        if (found == null) {
            diagnostics +=
                ReverseDiagnostic(
                    ReverseDiagnostic.Severity.WARN,
                    "REV-SQL-012",
                    "Foreign key '${p.sourceLabel}' references unknown column '$targetColumnName' " +
                        "on '${targetEntity.name}' — falling back to the target's primary key.",
                    file = fileHint,
                )
            return null
        }
        val soleTargetPk = targetEntity.attributes.singleOrNull { it.primaryKey }
        // Canonical form: when the referenced column *is* the target's sole primary key,
        // omit targetAttributeId — ErmForeignKey.targetAttributeId == null already means
        // "the primary key of the target entity".
        return if (soleTargetPk != null && soleTargetPk.id == found.id) null else found.id
    }

    // ── CREATE INDEX ─────────────────────────────────────────────────────────

    fun applyCreateIndex(
        ci: CreateIndex,
        entities: Map<String, MutableErmEntity>,
        nameIndex: Map<String, String>,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
    ) {
        val tableName = SqlIdentifiers.fold(ci.table.name)
        val entity = nameIndex[tableName]?.let { entities[it] }
        if (entity == null) {
            diagnostics +=
                ReverseDiagnostic(
                    ReverseDiagnostic.Severity.WARN,
                    "REV-SQL-002",
                    "CREATE INDEX references unknown table '$tableName' — skipped.",
                    file = fileHint,
                )
            return
        }
        val idx = ci.index
        val colNames = columnNames(idx)
        val attrIds = colNames.mapNotNull { n -> entity.attributes.firstOrNull { it.name == n }?.id }
        if (attrIds.size != colNames.size) {
            diagnostics +=
                ReverseDiagnostic(
                    ReverseDiagnostic.Severity.WARN,
                    "REV-SQL-002",
                    "CREATE INDEX '${idx.name}' on '$tableName' references unknown column(s) — partially mapped.",
                    file = fileHint,
                )
        }
        if (attrIds.isEmpty()) return
        entity.indexes +=
            ErmIndex(
                id = "idx_${entity.ix}_${entity.indexes.size}",
                name = idx.name?.let { SqlIdentifiers.fold(it) },
                attributeIds = attrIds,
                unique = idx.type?.equals("UNIQUE", ignoreCase = true) == true,
            )
    }

    // ── CREATE VIEW ──────────────────────────────────────────────────────────

    fun mapView(
        cv: CreateView,
        viewIx: Int,
        nameIndex: Map<String, String>,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
    ): ErmView {
        val name = SqlIdentifiers.fold(cv.view.name)
        val query = cv.select.toString()
        val referencedTableNames: Set<String> =
            try {
                // getTablesOrOtherSources (unlike the deprecated getTableList) also survives
                // CTEs/subqueries without throwing — best-effort resolution per stolperfalle #7.
                TablesNamesFinder<Void>().getTablesOrOtherSources(cv.select as JSqlStatement)
            } catch (_: Exception) {
                emptySet()
            }
        val referencedIds = mutableListOf<String>()
        for (t in referencedTableNames) {
            val folded = SqlIdentifiers.fold(t)
            val id = nameIndex[folded]
            if (id != null) {
                referencedIds += id
            } else {
                diagnostics +=
                    ReverseDiagnostic(
                        ReverseDiagnostic.Severity.INFO,
                        "REV-SQL-013",
                        "View '$name' references '$t', which could not be resolved to a known entity — best-effort only.",
                        file = fileHint,
                    )
            }
        }
        return ErmView(id = "view_$viewIx", name = name, query = query, referencedEntityIds = referencedIds)
    }
}
