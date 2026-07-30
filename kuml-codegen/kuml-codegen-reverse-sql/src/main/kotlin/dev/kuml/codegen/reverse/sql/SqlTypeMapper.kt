package dev.kuml.codegen.reverse.sql

import dev.kuml.erm.model.ErmDataType
import net.sf.jsqlparser.statement.create.table.ColDataType

/**
 * Inverse of `dev.kuml.codegen.sql.ErmSqlTypeMapper` (kuml-gen-sql, V3.4.7) —
 * maps a parsed Postgres [ColDataType] back to the dialect-neutral [ErmDataType]
 * sealed hierarchy (V3.4.9).
 *
 * JSqlParser bakes any parenthesized precision/scale/length argument directly
 * into [ColDataType.getDataType] as a single string (e.g. `"VARCHAR (255)"`,
 * `"NUMERIC (10, 2)"`) rather than populating `argumentsStringList` — [map]
 * splits that back apart on the first `(`.
 *
 * ### Known limitation: native Postgres `CREATE TYPE ... AS ENUM` (ADR-0016 retrofit)
 * This reverse engine only parses `CreateTable`/`Alter`/`CreateIndex`/`CreateView`
 * statements (see `PostgresErmReverseEngine`) — a `CREATE TYPE ... AS ENUM`
 * statement is never seen, so a column declared against such a native Postgres
 * enum type falls through to the `else` branch below and becomes
 * [ErmDataType.Custom] holding the raw type name (`REV-SQL-010` diagnostic),
 * not [ErmDataType.Enum]. This is an accepted, unchanged blind spot: the
 * forward direction ([dev.kuml.codegen.sql.ErmSqlTypeMapper]) never emits
 * `CREATE TYPE` in the first place (V3.4.7 deliberate VARCHAR+CHECK decision),
 * so round-tripping a kUML-generated schema is unaffected — only a *pre-existing*,
 * externally authored Postgres enum type hits this limitation.
 */
internal object SqlTypeMapper {
    /**
     * @property type the mapped dialect-neutral type.
     * @property autoIncrement `true` when the SQL type itself implies auto-increment
     *   semantics (the `SERIAL` family) — independent of whether the column also
     *   carries a `DEFAULT nextval(...)` expression (stolperfalle #6 of the plan;
     *   that case is detected separately in [ColumnMapper] from the column default).
     * @property diagnosticCode set when the mapping is lossy or falls back to a default,
     *   so the caller can emit the matching `REV-SQL-0xx` diagnostic.
     */
    data class Mapped(
        val type: ErmDataType,
        val autoIncrement: Boolean,
        val diagnosticCode: String? = null,
    )

    fun map(colDataType: ColDataType): Mapped {
        val raw = colDataType.dataType ?: ""
        val parenIx = raw.indexOf('(')
        val baseName =
            (if (parenIx >= 0) raw.substring(0, parenIx) else raw)
                .trim()
                .uppercase()
        val args =
            if (parenIx >= 0) {
                val closeIx = raw.lastIndexOf(')').let { if (it > parenIx) it else raw.length }
                raw
                    .substring(parenIx + 1, closeIx)
                    .split(",")
                    .map { it.trim().toIntOrNull() }
            } else {
                emptyList()
            }

        return when (baseName) {
            "SMALLINT", "INT2" -> Mapped(type = ErmDataType.Integer(16), autoIncrement = false)
            "INTEGER", "INT", "INT4" -> Mapped(type = ErmDataType.Integer(32), autoIncrement = false)
            "BIGINT", "INT8" -> Mapped(type = ErmDataType.Integer(64), autoIncrement = false)
            "SMALLSERIAL", "SERIAL2" -> Mapped(type = ErmDataType.Integer(16), autoIncrement = true)
            "SERIAL", "SERIAL4" -> Mapped(type = ErmDataType.Integer(32), autoIncrement = true)
            "BIGSERIAL", "SERIAL8" -> Mapped(type = ErmDataType.Integer(64), autoIncrement = true)
            "NUMERIC", "DECIMAL" -> {
                val precision = args.getOrNull(0)
                if (precision == null) {
                    // Bare NUMERIC/DECIMAL with no precision — SQL defines this as
                    // implementation-defined maximum precision; fall back to a wide
                    // Decimal rather than losing the column type entirely.
                    Mapped(type = ErmDataType.Decimal(precision = 38, scale = 0), autoIncrement = false, diagnosticCode = "REV-SQL-010")
                } else {
                    Mapped(type = ErmDataType.Decimal(precision = precision, scale = args.getOrNull(1) ?: 0), autoIncrement = false)
                }
            }
            "REAL", "FLOAT4" -> Mapped(type = ErmDataType.Real(double = false), autoIncrement = false)
            "DOUBLE PRECISION", "FLOAT8", "FLOAT" -> Mapped(type = ErmDataType.Real(double = true), autoIncrement = false)
            "VARCHAR", "CHARACTER VARYING" -> Mapped(type = ErmDataType.Varchar(args.getOrNull(0) ?: 255), autoIncrement = false)
            "CHAR", "CHARACTER" ->
                // CHAR(n) is fixed-length (blank-padded); ERM has no dedicated fixed-length
                // string type, so it maps to Varchar(n) — lossy (loses the padding semantics).
                Mapped(type = ErmDataType.Varchar(args.getOrNull(0) ?: 1), autoIncrement = false, diagnosticCode = "REV-SQL-015")
            "TEXT" -> Mapped(type = ErmDataType.Text, autoIncrement = false)
            "BOOLEAN", "BOOL" -> Mapped(type = ErmDataType.Boolean, autoIncrement = false)
            "DATE" -> Mapped(type = ErmDataType.Date, autoIncrement = false)
            "TIME" -> Mapped(type = ErmDataType.Time, autoIncrement = false)
            "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> Mapped(type = ErmDataType.Timestamp(withTimeZone = false), autoIncrement = false)
            "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> Mapped(type = ErmDataType.Timestamp(withTimeZone = true), autoIncrement = false)
            "UUID" -> Mapped(type = ErmDataType.Uuid, autoIncrement = false)
            "BYTEA", "BLOB" -> Mapped(type = ErmDataType.Blob, autoIncrement = false)
            "JSON", "JSONB" -> Mapped(type = ErmDataType.Json, autoIncrement = false)
            else -> Mapped(type = ErmDataType.Custom(raw.trim()), autoIncrement = false, diagnosticCode = "REV-SQL-010")
        }
    }
}
