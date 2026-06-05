package dev.kuml.codegen.sql

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlTypeRef

internal object SqlTypeMapper {
    fun toSqlType(
        typeRef: UmlTypeRef,
        dialect: SqlDialect,
    ): String =
        when (typeRef.name) {
            "Int", "Integer", "int" -> "INTEGER"
            "Long", "long" ->
                when (dialect) {
                    SqlDialect.SQLITE -> "INTEGER"
                    else -> "BIGINT"
                }
            "Short" -> "SMALLINT"
            "Double", "double", "Float", "float" ->
                when (dialect) {
                    SqlDialect.POSTGRES -> "DOUBLE PRECISION"
                    SqlDialect.MYSQL, SqlDialect.H2 -> "DOUBLE"
                    SqlDialect.SQLITE -> "REAL"
                }
            "Boolean", "boolean", "bool" ->
                when (dialect) {
                    SqlDialect.POSTGRES, SqlDialect.H2 -> "BOOLEAN"
                    SqlDialect.MYSQL -> "TINYINT(1)"
                    SqlDialect.SQLITE -> "INTEGER"
                }
            "String", "string", "str" -> "VARCHAR(255)"
            "Text" -> "TEXT"
            "UUID" ->
                when (dialect) {
                    SqlDialect.POSTGRES, SqlDialect.H2 -> "UUID"
                    SqlDialect.MYSQL -> "CHAR(36)"
                    SqlDialect.SQLITE -> "TEXT"
                }
            "Date", "LocalDate" -> "DATE"
            "DateTime", "LocalDateTime", "Instant" ->
                when (dialect) {
                    SqlDialect.POSTGRES, SqlDialect.H2 -> "TIMESTAMP"
                    SqlDialect.MYSQL -> "DATETIME"
                    SqlDialect.SQLITE -> "TEXT"
                }
            "Time", "LocalTime" -> "TIME"
            "BigDecimal" -> "DECIMAL(18, 2)"
            "BigInteger" -> "DECIMAL(38, 0)"
            else -> "VARCHAR(255)"
        }

    fun nullClause(multiplicity: Multiplicity): String = if (multiplicity.lower >= 1) "NOT NULL" else "NULL"

    fun isManyToManyCandidate(multiplicity: Multiplicity): Boolean {
        val upper = multiplicity.upper
        return upper == null || upper > 1
    }
}
