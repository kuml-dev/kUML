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
            "SMALLINT", "INT2" -> Mapped(ErmDataType.Integer(16), false)
            "INTEGER", "INT", "INT4" -> Mapped(ErmDataType.Integer(32), false)
            "BIGINT", "INT8" -> Mapped(ErmDataType.Integer(64), false)
            "SMALLSERIAL", "SERIAL2" -> Mapped(ErmDataType.Integer(16), true)
            "SERIAL", "SERIAL4" -> Mapped(ErmDataType.Integer(32), true)
            "BIGSERIAL", "SERIAL8" -> Mapped(ErmDataType.Integer(64), true)
            "NUMERIC", "DECIMAL" -> {
                val precision = args.getOrNull(0)
                if (precision == null) {
                    // Bare NUMERIC/DECIMAL with no precision — SQL defines this as
                    // implementation-defined maximum precision; fall back to a wide
                    // Decimal rather than losing the column type entirely.
                    Mapped(ErmDataType.Decimal(38, 0), false, "REV-SQL-010")
                } else {
                    Mapped(ErmDataType.Decimal(precision, args.getOrNull(1) ?: 0), false)
                }
            }
            "REAL", "FLOAT4" -> Mapped(ErmDataType.Real(double = false), false)
            "DOUBLE PRECISION", "FLOAT8", "FLOAT" -> Mapped(ErmDataType.Real(double = true), false)
            "VARCHAR", "CHARACTER VARYING" -> Mapped(ErmDataType.Varchar(args.getOrNull(0) ?: 255), false)
            "CHAR", "CHARACTER" ->
                // CHAR(n) is fixed-length (blank-padded); ERM has no dedicated fixed-length
                // string type, so it maps to Varchar(n) — lossy (loses the padding semantics).
                Mapped(ErmDataType.Varchar(args.getOrNull(0) ?: 1), false, "REV-SQL-015")
            "TEXT" -> Mapped(ErmDataType.Text, false)
            "BOOLEAN", "BOOL" -> Mapped(ErmDataType.Boolean, false)
            "DATE" -> Mapped(ErmDataType.Date, false)
            "TIME" -> Mapped(ErmDataType.Time, false)
            "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> Mapped(ErmDataType.Timestamp(withTimeZone = false), false)
            "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> Mapped(ErmDataType.Timestamp(withTimeZone = true), false)
            "UUID" -> Mapped(ErmDataType.Uuid, false)
            "BYTEA", "BLOB" -> Mapped(ErmDataType.Blob, false)
            "JSON", "JSONB" -> Mapped(ErmDataType.Json, false)
            else -> Mapped(ErmDataType.Custom(raw.trim()), false, "REV-SQL-010")
        }
    }
}
