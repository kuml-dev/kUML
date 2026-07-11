package dev.kuml.codegen.sql

import dev.kuml.codegen.api.customtype.CustomTypeHooks
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType

/**
 * V3.4.7 — dialect-aware column type rendering for [ErmSqlEmitter].
 *
 * Replaces the old UML-only `SqlTypeMapper` (deleted). Maps the dialect-neutral
 * [ErmDataType] sealed hierarchy exhaustively across all four [SqlDialect]s, plus
 * the `autoIncrement` special-casing (`SERIAL`-family / `AUTO_INCREMENT` / bare
 * `INTEGER` for SQLite's rowid alias — see stolperfalle 6 in the V3.4.7 plan:
 * SQLite only allows `AUTOINCREMENT` on a single-column `INTEGER` primary key, so
 * this mapper deliberately never emits the `AUTOINCREMENT` keyword itself and
 * relies on SQLite's implicit rowid-aliasing behaviour instead).
 */
internal object ErmSqlTypeMapper {
    /** Full column type declaration, including `autoIncrement` handling (`SERIAL`/`AUTO_INCREMENT`/…). */
    fun columnType(
        attr: ErmAttribute,
        dialect: SqlDialect,
    ): String {
        val type = attr.type
        return if (attr.autoIncrement && type is ErmDataType.Integer) {
            autoIncrementType(type, dialect)
        } else {
            baseType(type, dialect)
        }
    }

    private fun autoIncrementType(
        type: ErmDataType.Integer,
        dialect: SqlDialect,
    ): String =
        when (dialect) {
            SqlDialect.POSTGRES ->
                when (type.bits) {
                    16 -> "SMALLSERIAL"
                    64 -> "BIGSERIAL"
                    else -> "SERIAL"
                }
            SqlDialect.MYSQL, SqlDialect.H2 -> "${baseType(type, dialect)} AUTO_INCREMENT"
            // SQLite: AUTOINCREMENT is only legal on a single-column INTEGER PK and is
            // never required for rowid semantics — emit the bare type and let SQLite's
            // implicit rowid-aliasing provide the auto-increment behaviour.
            SqlDialect.SQLITE -> "INTEGER"
        }

    /** Basistyp ohne `autoIncrement` — exhaustives `when` über die [ErmDataType]-Hierarchie. */
    fun baseType(
        type: ErmDataType,
        dialect: SqlDialect,
    ): String =
        when (type) {
            is ErmDataType.Integer ->
                when (type.bits) {
                    16 -> "SMALLINT"
                    64 -> "BIGINT"
                    else -> "INTEGER"
                }
            is ErmDataType.Decimal -> "DECIMAL(${type.precision}, ${type.scale})"
            is ErmDataType.Real ->
                if (type.double) {
                    when (dialect) {
                        SqlDialect.POSTGRES -> "DOUBLE PRECISION"
                        SqlDialect.MYSQL, SqlDialect.H2 -> "DOUBLE"
                        SqlDialect.SQLITE -> "REAL"
                    }
                } else {
                    when (dialect) {
                        SqlDialect.POSTGRES, SqlDialect.H2, SqlDialect.SQLITE -> "REAL"
                        SqlDialect.MYSQL -> "FLOAT"
                    }
                }
            is ErmDataType.Varchar -> "VARCHAR(${type.length})"
            is ErmDataType.Text -> "TEXT"
            is ErmDataType.Boolean ->
                when (dialect) {
                    SqlDialect.POSTGRES, SqlDialect.H2 -> "BOOLEAN"
                    SqlDialect.MYSQL -> "TINYINT(1)"
                    SqlDialect.SQLITE -> "INTEGER"
                }
            is ErmDataType.Date -> "DATE"
            is ErmDataType.Time -> "TIME"
            is ErmDataType.Timestamp ->
                when (dialect) {
                    SqlDialect.SQLITE -> "TEXT"
                    SqlDialect.POSTGRES -> if (type.withTimeZone) "TIMESTAMPTZ" else "TIMESTAMP"
                    SqlDialect.MYSQL -> if (type.withTimeZone) "TIMESTAMP" else "DATETIME"
                    SqlDialect.H2 -> if (type.withTimeZone) "TIMESTAMP WITH TIME ZONE" else "TIMESTAMP"
                }
            is ErmDataType.Uuid ->
                when (dialect) {
                    SqlDialect.POSTGRES, SqlDialect.H2 -> "UUID"
                    SqlDialect.MYSQL -> "CHAR(36)"
                    SqlDialect.SQLITE -> "TEXT"
                }
            is ErmDataType.Blob ->
                when (dialect) {
                    SqlDialect.POSTGRES -> "BYTEA"
                    SqlDialect.MYSQL, SqlDialect.H2, SqlDialect.SQLITE -> "BLOB"
                }
            is ErmDataType.Json ->
                when (dialect) {
                    SqlDialect.POSTGRES -> "JSONB"
                    SqlDialect.MYSQL, SqlDialect.H2 -> "JSON"
                    SqlDialect.SQLITE -> "TEXT"
                }
            is ErmDataType.Custom -> {
                // ADR-0016 §2.3 — recognized PostGIS geometry strings are normalized to a
                // canonical Postgres type; every other dialect (and any unrecognized Custom
                // string, on any dialect) is unchanged verbatim behavior.
                val geo = CustomTypeHooks.recognize(type.raw)
                if (geo != null && dialect == SqlDialect.POSTGRES) geo.postgresType() else type.raw
            }
        }
}
