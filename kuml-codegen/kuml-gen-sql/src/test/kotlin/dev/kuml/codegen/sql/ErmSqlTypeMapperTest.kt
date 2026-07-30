package dev.kuml.codegen.sql

import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun attr(
    type: ErmDataType,
    autoIncrement: Boolean = false,
): ErmAttribute = ErmAttribute(id = "a", name = "col", type = type, autoIncrement = autoIncrement)

class ErmSqlTypeMapperTest :
    FunSpec({

        test("Integer bits map to SMALLINT/INTEGER/BIGINT for every dialect") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(type = ErmDataType.Integer(16), dialect = dialect) shouldBe "SMALLINT"
                ErmSqlTypeMapper.baseType(type = ErmDataType.Integer(32), dialect = dialect) shouldBe "INTEGER"
                ErmSqlTypeMapper.baseType(type = ErmDataType.Integer(64), dialect = dialect) shouldBe "BIGINT"
            }
        }

        test("Decimal renders precision/scale identically for every dialect") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(type = ErmDataType.Decimal(precision = 10, scale = 2), dialect = dialect) shouldBe
                    "DECIMAL(10, 2)"
            }
        }

        test("Real is dialect-specific") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Real(double = true), dialect = SqlDialect.POSTGRES) shouldBe "DOUBLE PRECISION"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Real(double = true), dialect = SqlDialect.MYSQL) shouldBe "DOUBLE"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Real(double = true), dialect = SqlDialect.H2) shouldBe "DOUBLE"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Real(double = true), dialect = SqlDialect.SQLITE) shouldBe "REAL"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Real(double = false), dialect = SqlDialect.MYSQL) shouldBe "FLOAT"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Real(double = false), dialect = SqlDialect.POSTGRES) shouldBe "REAL"
        }

        test("Varchar/Text are dialect-neutral") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(type = ErmDataType.Varchar(64), dialect = dialect) shouldBe "VARCHAR(64)"
                ErmSqlTypeMapper.baseType(type = ErmDataType.Text, dialect = dialect) shouldBe "TEXT"
            }
        }

        test("Enum renders as VARCHAR(longest literal) for every dialect (no CREATE TYPE)") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(
                    type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                    dialect = dialect,
                ) shouldBe "VARCHAR(8)"
            }
        }

        test("Boolean is dialect-specific") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Boolean, dialect = SqlDialect.POSTGRES) shouldBe "BOOLEAN"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Boolean, dialect = SqlDialect.H2) shouldBe "BOOLEAN"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Boolean, dialect = SqlDialect.MYSQL) shouldBe "TINYINT(1)"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Boolean, dialect = SqlDialect.SQLITE) shouldBe "INTEGER"
        }

        test("Date/Time are dialect-neutral") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(type = ErmDataType.Date, dialect = dialect) shouldBe "DATE"
                ErmSqlTypeMapper.baseType(type = ErmDataType.Time, dialect = dialect) shouldBe "TIME"
            }
        }

        test("Timestamp without timezone is dialect-specific") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Timestamp(withTimeZone = false), dialect = SqlDialect.POSTGRES) shouldBe
                "TIMESTAMP"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Timestamp(withTimeZone = false), dialect = SqlDialect.H2) shouldBe "TIMESTAMP"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Timestamp(withTimeZone = false), dialect = SqlDialect.MYSQL) shouldBe "DATETIME"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Timestamp(withTimeZone = false), dialect = SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("Timestamp with timezone is dialect-specific") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Timestamp(withTimeZone = true), dialect = SqlDialect.POSTGRES) shouldBe
                "TIMESTAMPTZ"
            ErmSqlTypeMapper.baseType(
                type = ErmDataType.Timestamp(withTimeZone = true),
                dialect = SqlDialect.H2,
            ) shouldBe "TIMESTAMP WITH TIME ZONE"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Timestamp(withTimeZone = true), dialect = SqlDialect.MYSQL) shouldBe "TIMESTAMP"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Timestamp(withTimeZone = true), dialect = SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("UUID is dialect-specific") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Uuid, dialect = SqlDialect.POSTGRES) shouldBe "UUID"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Uuid, dialect = SqlDialect.H2) shouldBe "UUID"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Uuid, dialect = SqlDialect.MYSQL) shouldBe "CHAR(36)"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Uuid, dialect = SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("Blob is dialect-specific") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Blob, dialect = SqlDialect.POSTGRES) shouldBe "BYTEA"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Blob, dialect = SqlDialect.MYSQL) shouldBe "BLOB"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Blob, dialect = SqlDialect.H2) shouldBe "BLOB"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Blob, dialect = SqlDialect.SQLITE) shouldBe "BLOB"
        }

        test("Json is dialect-specific") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Json, dialect = SqlDialect.POSTGRES) shouldBe "JSONB"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Json, dialect = SqlDialect.MYSQL) shouldBe "JSON"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Json, dialect = SqlDialect.H2) shouldBe "JSON"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Json, dialect = SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("Custom passes the raw string through verbatim, regardless of dialect") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(type = ErmDataType.Custom("tsvector"), dialect = dialect) shouldBe "tsvector"
            }
        }

        // ── PostGIS geometry recognition (ADR-0016 §2.3) ─────────────────────────

        test("recognized PostGIS geometry Custom types normalize to canonical form on POSTGRES") {
            ErmSqlTypeMapper.baseType(
                type = ErmDataType.Custom("geometry(Point,4326)"),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe "geometry(Point,4326)"
            ErmSqlTypeMapper.baseType(
                type = ErmDataType.Custom("  geometry( polygon )  "),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe "geometry(Polygon)"
            ErmSqlTypeMapper.baseType(
                type = ErmDataType.Custom("GEOMETRY(LineString, 3857)"),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe "geometry(LineString,3857)"
            ErmSqlTypeMapper.baseType(
                type = ErmDataType.Custom("geometry(geometry)"),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe "geometry(Geometry)"
        }

        test("recognized PostGIS geometry Custom types are unchanged verbatim on non-Postgres dialects") {
            listOf(SqlDialect.MYSQL, SqlDialect.H2, SqlDialect.SQLITE).forEach { dialect ->
                ErmSqlTypeMapper.baseType(type = ErmDataType.Custom("geometry(Point,4326)"), dialect = dialect) shouldBe
                    "geometry(Point,4326)"
            }
        }

        test("unrecognized Custom strings fall back to verbatim even on POSTGRES") {
            ErmSqlTypeMapper.baseType(type = ErmDataType.Custom("tsvector"), dialect = SqlDialect.POSTGRES) shouldBe "tsvector"
            ErmSqlTypeMapper.baseType(type = ErmDataType.Custom("geometry(circle,4326)"), dialect = SqlDialect.POSTGRES) shouldBe
                "geometry(circle,4326)"
        }

        test("an over-long SRID is not recognized and falls back to verbatim (DoS guard)") {
            ErmSqlTypeMapper.baseType(
                type = ErmDataType.Custom("geometry(Point,12345678901234)"),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe "geometry(Point,12345678901234)"
        }

        // ── autoIncrement / columnType ───────────────────────────────────────────

        test("autoIncrement Integer(64) maps to BIGSERIAL/AUTO_INCREMENT/INTEGER per dialect") {
            ErmSqlTypeMapper.columnType(
                attr = attr(type = ErmDataType.Integer(64), autoIncrement = true),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe
                "BIGSERIAL"
            ErmSqlTypeMapper.columnType(
                attr = attr(type = ErmDataType.Integer(64), autoIncrement = true),
                dialect = SqlDialect.MYSQL,
            ) shouldBe "BIGINT AUTO_INCREMENT"
            ErmSqlTypeMapper.columnType(
                attr = attr(type = ErmDataType.Integer(64), autoIncrement = true),
                dialect = SqlDialect.H2,
            ) shouldBe "BIGINT AUTO_INCREMENT"
            ErmSqlTypeMapper.columnType(
                attr = attr(type = ErmDataType.Integer(64), autoIncrement = true),
                dialect = SqlDialect.SQLITE,
            ) shouldBe
                "INTEGER"
        }

        test("autoIncrement Integer(32) maps to SERIAL for Postgres") {
            ErmSqlTypeMapper.columnType(
                attr = attr(type = ErmDataType.Integer(32), autoIncrement = true),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe
                "SERIAL"
        }

        test("autoIncrement Integer(16) maps to SMALLSERIAL for Postgres") {
            ErmSqlTypeMapper.columnType(
                attr = attr(type = ErmDataType.Integer(16), autoIncrement = true),
                dialect = SqlDialect.POSTGRES,
            ) shouldBe "SMALLSERIAL"
        }

        test("autoIncrement on a non-Integer type is ignored — base type is rendered unchanged") {
            ErmSqlTypeMapper.columnType(attr = attr(type = ErmDataType.Uuid, autoIncrement = true), dialect = SqlDialect.POSTGRES) shouldBe
                "UUID"
        }

        test("non-autoIncrement column renders the plain base type") {
            ErmSqlTypeMapper.columnType(attr = attr(type = ErmDataType.Integer(64)), dialect = SqlDialect.POSTGRES) shouldBe "BIGINT"
        }
    })
