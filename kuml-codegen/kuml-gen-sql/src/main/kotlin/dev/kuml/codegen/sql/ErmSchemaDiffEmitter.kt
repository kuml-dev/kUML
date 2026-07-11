package dev.kuml.codegen.sql

import dev.kuml.erm.model.ErmModel

/**
 * ADR-0016 (deferred item) — renders an [ErmSchemaDiff] as a single additive
 * SQL migration string. Every fragment is delegated to [ErmSqlEmitter]'s
 * `internal` per-element renderers (never re-implemented here), so identifier
 * safety ([SqlNames.requireSafe]), dialect-aware type mapping
 * ([ErmSqlTypeMapper]), and literal-injection guards (e.g. the hypertable
 * chunk-interval whitelist) are inherited for free.
 *
 * Statement order mirrors [ErmSqlEmitter.emit]'s block ordering — new tables,
 * then hypertables (must be declared on the still-empty freshly-created
 * table), then new columns, then foreign keys, then indexes, then views, then
 * new check constraints.
 */
internal class ErmSchemaDiffEmitter(
    private val dialect: SqlDialect,
    private val options: SqlEmitOptions,
) {
    private val emitter = ErmSqlEmitter(dialect, options)

    fun emit(
        oldModel: ErmModel,
        newModel: ErmModel,
        diff: ErmSchemaDiff,
    ): String {
        val sb = StringBuilder()

        if (options.schemaComment) {
            sb.appendLine(
                "-- kUML additive schema migration: '${oldModel.name}' -> '${newModel.name}' (dialect: ${dialect.key})",
            )
            sb.appendLine()
        }

        if (diff.newEntities.isNotEmpty()) {
            sb.appendLine("-- New tables")
            sb.appendLine()
            for (entity in diff.newEntities) sb.append(emitter.renderCreateTable(entity))
        }

        val hypertableStatements = diff.newEntities.mapNotNull { emitter.renderHypertableStatementOrNull(it) }
        if (hypertableStatements.isNotEmpty()) {
            sb.appendLine("-- TimescaleDB hypertables")
            sb.appendLine()
            hypertableStatements.forEach { sb.append(it) }
            sb.appendLine()
        }

        if (diff.newAttributes.isNotEmpty()) {
            sb.appendLine("-- New columns")
            sb.appendLine()
            for (added in diff.newAttributes) sb.append(emitter.renderAddColumnStatement(added.entity, added.attribute))
            sb.appendLine()
        }

        val fkStatements = mutableListOf<String>()
        for (entity in diff.newEntities) {
            for (attr in entity.attributes) {
                emitter.renderForeignKeyConstraintOrNull(entity, attr, newModel)?.let { fkStatements += it }
            }
        }
        for (added in diff.newAttributes) {
            emitter.renderForeignKeyConstraintOrNull(added.entity, added.attribute, newModel)?.let { fkStatements += it }
        }
        if (fkStatements.isNotEmpty()) {
            sb.appendLine("-- Foreign Keys")
            sb.appendLine()
            fkStatements.forEach { sb.append(it) }
            sb.appendLine()
        }

        val indexStatements = mutableListOf<String>()
        for (entity in diff.newEntities) {
            for (index in entity.indexes) indexStatements += emitter.renderIndexStatement(entity, index)
        }
        for (added in diff.newIndexes) indexStatements += emitter.renderIndexStatement(added.entity, added.index)
        if (indexStatements.isNotEmpty()) {
            sb.appendLine("-- Indexes")
            sb.appendLine()
            indexStatements.forEach { sb.append(it) }
            sb.appendLine()
        }

        if (diff.newViews.isNotEmpty()) {
            sb.appendLine("-- Views")
            sb.appendLine()
            for (view in diff.newViews) sb.append(emitter.renderViewStatement(view))
        }

        if (diff.newChecks.isNotEmpty()) {
            sb.appendLine("-- New check constraints")
            sb.appendLine()
            for (added in diff.newChecks) sb.append(emitter.renderCheckConstraintStatement(added.entity, added.check))
            sb.appendLine()
        }

        return sb.toString()
    }
}
