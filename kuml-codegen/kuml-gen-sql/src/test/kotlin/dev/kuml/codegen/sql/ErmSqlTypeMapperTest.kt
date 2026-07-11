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
                ErmSqlTypeMapper.baseType(ErmDataType.Integer(16), dialect) shouldBe "SMALLINT"
                ErmSqlTypeMapper.baseType(ErmDataType.Integer(32), dialect) shouldBe "INTEGER"
                ErmSqlTypeMapper.baseType(ErmDataType.Integer(64), dialect) shouldBe "BIGINT"
            }
        }

        test("Decimal renders precision/scale identically for every dialect") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(ErmDataType.Decimal(10, 2), dialect) shouldBe "DECIMAL(10, 2)"
            }
        }

        test("Real is dialect-specific") {
            ErmSqlTypeMapper.baseType(ErmDataType.Real(double = true), SqlDialect.POSTGRES) shouldBe "DOUBLE PRECISION"
            ErmSqlTypeMapper.baseType(ErmDataType.Real(double = true), SqlDialect.MYSQL) shouldBe "DOUBLE"
            ErmSqlTypeMapper.baseType(ErmDataType.Real(double = true), SqlDialect.H2) shouldBe "DOUBLE"
            ErmSqlTypeMapper.baseType(ErmDataType.Real(double = true), SqlDialect.SQLITE) shouldBe "REAL"
            ErmSqlTypeMapper.baseType(ErmDataType.Real(double = false), SqlDialect.MYSQL) shouldBe "FLOAT"
            ErmSqlTypeMapper.baseType(ErmDataType.Real(double = false), SqlDialect.POSTGRES) shouldBe "REAL"
        }

        test("Varchar/Text are dialect-neutral") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(ErmDataType.Varchar(64), dialect) shouldBe "VARCHAR(64)"
                ErmSqlTypeMapper.baseType(ErmDataType.Text, dialect) shouldBe "TEXT"
            }
        }

        test("Boolean is dialect-specific") {
            ErmSqlTypeMapper.baseType(ErmDataType.Boolean, SqlDialect.POSTGRES) shouldBe "BOOLEAN"
            ErmSqlTypeMapper.baseType(ErmDataType.Boolean, SqlDialect.H2) shouldBe "BOOLEAN"
            ErmSqlTypeMapper.baseType(ErmDataType.Boolean, SqlDialect.MYSQL) shouldBe "TINYINT(1)"
            ErmSqlTypeMapper.baseType(ErmDataType.Boolean, SqlDialect.SQLITE) shouldBe "INTEGER"
        }

        test("Date/Time are dialect-neutral") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(ErmDataType.Date, dialect) shouldBe "DATE"
                ErmSqlTypeMapper.baseType(ErmDataType.Time, dialect) shouldBe "TIME"
            }
        }

        test("Timestamp without timezone is dialect-specific") {
            ErmSqlTypeMapper.baseType(ErmDataType.Timestamp(withTimeZone = false), SqlDialect.POSTGRES) shouldBe "TIMESTAMP"
            ErmSqlTypeMapper.baseType(ErmDataType.Timestamp(withTimeZone = false), SqlDialect.H2) shouldBe "TIMESTAMP"
            ErmSqlTypeMapper.baseType(ErmDataType.Timestamp(withTimeZone = false), SqlDialect.MYSQL) shouldBe "DATETIME"
            ErmSqlTypeMapper.baseType(ErmDataType.Timestamp(withTimeZone = false), SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("Timestamp with timezone is dialect-specific") {
            ErmSqlTypeMapper.baseType(ErmDataType.Timestamp(withTimeZone = true), SqlDialect.POSTGRES) shouldBe "TIMESTAMPTZ"
            ErmSqlTypeMapper.baseType(
                ErmDataType.Timestamp(withTimeZone = true),
                SqlDialect.H2,
            ) shouldBe "TIMESTAMP WITH TIME ZONE"
            ErmSqlTypeMapper.baseType(ErmDataType.Timestamp(withTimeZone = true), SqlDialect.MYSQL) shouldBe "TIMESTAMP"
            ErmSqlTypeMapper.baseType(ErmDataType.Timestamp(withTimeZone = true), SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("UUID is dialect-specific") {
            ErmSqlTypeMapper.baseType(ErmDataType.Uuid, SqlDialect.POSTGRES) shouldBe "UUID"
            ErmSqlTypeMapper.baseType(ErmDataType.Uuid, SqlDialect.H2) shouldBe "UUID"
            ErmSqlTypeMapper.baseType(ErmDataType.Uuid, SqlDialect.MYSQL) shouldBe "CHAR(36)"
            ErmSqlTypeMapper.baseType(ErmDataType.Uuid, SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("Blob is dialect-specific") {
            ErmSqlTypeMapper.baseType(ErmDataType.Blob, SqlDialect.POSTGRES) shouldBe "BYTEA"
            ErmSqlTypeMapper.baseType(ErmDataType.Blob, SqlDialect.MYSQL) shouldBe "BLOB"
            ErmSqlTypeMapper.baseType(ErmDataType.Blob, SqlDialect.H2) shouldBe "BLOB"
            ErmSqlTypeMapper.baseType(ErmDataType.Blob, SqlDialect.SQLITE) shouldBe "BLOB"
        }

        test("Json is dialect-specific") {
            ErmSqlTypeMapper.baseType(ErmDataType.Json, SqlDialect.POSTGRES) shouldBe "JSONB"
            ErmSqlTypeMapper.baseType(ErmDataType.Json, SqlDialect.MYSQL) shouldBe "JSON"
            ErmSqlTypeMapper.baseType(ErmDataType.Json, SqlDialect.H2) shouldBe "JSON"
            ErmSqlTypeMapper.baseType(ErmDataType.Json, SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("Custom passes the raw string through verbatim, regardless of dialect") {
            SqlDialect.entries.forEach { dialect ->
                ErmSqlTypeMapper.baseType(ErmDataType.Custom("tsvector"), dialect) shouldBe "tsvector"
            }
        }

        // ── PostGIS geometry recognition (ADR-0016 §2.3) ─────────────────────────

        test("recognized PostGIS geometry Custom types normalize to canonical form on POSTGRES") {
            ErmSqlTypeMapper.baseType(
                ErmDataType.Custom("geometry(Point,4326)"),
                SqlDialect.POSTGRES,
            ) shouldBe "geometry(Point,4326)"
            ErmSqlTypeMapper.baseType(
                ErmDataType.Custom("  geometry( polygon )  "),
                SqlDialect.POSTGRES,
            ) shouldBe "geometry(Polygon)"
            ErmSqlTypeMapper.baseType(
                ErmDataType.Custom("GEOMETRY(LineString, 3857)"),
                SqlDialect.POSTGRES,
            ) shouldBe "geometry(LineString,3857)"
            ErmSqlTypeMapper.baseType(
                ErmDataType.Custom("geometry(geometry)"),
                SqlDialect.POSTGRES,
            ) shouldBe "geometry(Geometry)"
        }

        test("recognized PostGIS geometry Custom types are unchanged verbatim on non-Postgres dialects") {
            listOf(SqlDialect.MYSQL, SqlDialect.H2, SqlDialect.SQLITE).forEach { dialect ->
                ErmSqlTypeMapper.baseType(ErmDataType.Custom("geometry(Point,4326)"), dialect) shouldBe "geometry(Point,4326)"
            }
        }

        test("unrecognized Custom strings fall back to verbatim even on POSTGRES") {
            ErmSqlTypeMapper.baseType(ErmDataType.Custom("tsvector"), SqlDialect.POSTGRES) shouldBe "tsvector"
            ErmSqlTypeMapper.baseType(ErmDataType.Custom("geometry(circle,4326)"), SqlDialect.POSTGRES) shouldBe "geometry(circle,4326)"
        }

        test("an over-long SRID is not recognized and falls back to verbatim (DoS guard)") {
            ErmSqlTypeMapper.baseType(
                ErmDataType.Custom("geometry(Point,12345678901234)"),
                SqlDialect.POSTGRES,
            ) shouldBe "geometry(Point,12345678901234)"
        }

        // ── autoIncrement / columnType ───────────────────────────────────────────

        test("autoIncrement Integer(64) maps to BIGSERIAL/AUTO_INCREMENT/INTEGER per dialect") {
            ErmSqlTypeMapper.columnType(attr(ErmDataType.Integer(64), autoIncrement = true), SqlDialect.POSTGRES) shouldBe "BIGSERIAL"
            ErmSqlTypeMapper.columnType(
                attr(ErmDataType.Integer(64), autoIncrement = true),
                SqlDialect.MYSQL,
            ) shouldBe "BIGINT AUTO_INCREMENT"
            ErmSqlTypeMapper.columnType(
                attr(ErmDataType.Integer(64), autoIncrement = true),
                SqlDialect.H2,
            ) shouldBe "BIGINT AUTO_INCREMENT"
            ErmSqlTypeMapper.columnType(attr(ErmDataType.Integer(64), autoIncrement = true), SqlDialect.SQLITE) shouldBe "INTEGER"
        }

        test("autoIncrement Integer(32) maps to SERIAL for Postgres") {
            ErmSqlTypeMapper.columnType(attr(ErmDataType.Integer(32), autoIncrement = true), SqlDialect.POSTGRES) shouldBe "SERIAL"
        }

        test("autoIncrement Integer(16) maps to SMALLSERIAL for Postgres") {
            ErmSqlTypeMapper.columnType(
                attr(ErmDataType.Integer(16), autoIncrement = true),
                SqlDialect.POSTGRES,
            ) shouldBe "SMALLSERIAL"
        }

        test("autoIncrement on a non-Integer type is ignored — base type is rendered unchanged") {
            ErmSqlTypeMapper.columnType(attr(ErmDataType.Uuid, autoIncrement = true), SqlDialect.POSTGRES) shouldBe "UUID"
        }

        test("non-autoIncrement column renders the plain base type") {
            ErmSqlTypeMapper.columnType(attr(ErmDataType.Integer(64)), SqlDialect.POSTGRES) shouldBe "BIGINT"
        }
    })
