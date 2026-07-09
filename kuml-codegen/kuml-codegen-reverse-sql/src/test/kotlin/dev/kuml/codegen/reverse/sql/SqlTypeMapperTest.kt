package dev.kuml.codegen.reverse.sql

import dev.kuml.erm.model.ErmDataType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.create.table.ColumnDefinition
import net.sf.jsqlparser.statement.create.table.CreateTable

/**
 * Exercises [SqlTypeMapper] against real JSqlParser-parsed [net.sf.jsqlparser.statement.create.table.ColDataType]
 * instances (not hand-built ones) — JSqlParser bakes precision/scale/length args
 * directly into the data type string (`"VARCHAR (255)"`), which is the exact
 * shape [SqlTypeMapper.map] must split back apart.
 */
class SqlTypeMapperTest :
    FunSpec({

        fun columnsOf(ddl: String): Map<String, ColumnDefinition> {
            val ct = CCJSqlParserUtil.parse(ddl) as CreateTable
            return ct.columnDefinitions.associateBy { it.columnName }
        }

        val ddl =
            """
            CREATE TABLE t (
              a SMALLINT, b INTEGER, c BIGINT, d INT2, e INT4, f INT8,
              g SMALLSERIAL, h SERIAL, i BIGSERIAL,
              j NUMERIC(10,2), k DECIMAL(5), l NUMERIC,
              m REAL, n DOUBLE PRECISION,
              o VARCHAR(255), p CHARACTER VARYING(50), q CHAR(3),
              r TEXT, s BOOLEAN, t2 BOOL,
              u DATE, v TIME, w TIMESTAMP, x TIMESTAMPTZ,
              y TIMESTAMP WITHOUT TIME ZONE, z TIMESTAMP WITH TIME ZONE,
              aa UUID, bb BYTEA, cc JSON, dd JSONB, ee TSVECTOR
            )
            """.trimIndent()
        val cols = columnsOf(ddl)

        test("integer family maps by bit width") {
            SqlTypeMapper.map(cols.getValue("a").colDataType).type shouldBe ErmDataType.Integer(16)
            SqlTypeMapper.map(cols.getValue("b").colDataType).type shouldBe ErmDataType.Integer(32)
            SqlTypeMapper.map(cols.getValue("c").colDataType).type shouldBe ErmDataType.Integer(64)
            SqlTypeMapper.map(cols.getValue("d").colDataType).type shouldBe ErmDataType.Integer(16)
            SqlTypeMapper.map(cols.getValue("e").colDataType).type shouldBe ErmDataType.Integer(32)
            SqlTypeMapper.map(cols.getValue("f").colDataType).type shouldBe ErmDataType.Integer(64)
        }

        test("SERIAL family maps to Integer with autoIncrement=true") {
            val g = SqlTypeMapper.map(cols.getValue("g").colDataType)
            g.type shouldBe ErmDataType.Integer(16)
            g.autoIncrement shouldBe true

            val h = SqlTypeMapper.map(cols.getValue("h").colDataType)
            h.type shouldBe ErmDataType.Integer(32)
            h.autoIncrement shouldBe true

            val i = SqlTypeMapper.map(cols.getValue("i").colDataType)
            i.type shouldBe ErmDataType.Integer(64)
            i.autoIncrement shouldBe true
        }

        test("NUMERIC/DECIMAL with precision and scale") {
            SqlTypeMapper.map(cols.getValue("j").colDataType).type shouldBe ErmDataType.Decimal(10, 2)
            SqlTypeMapper.map(cols.getValue("k").colDataType).type shouldBe ErmDataType.Decimal(5, 0)
        }

        test("bare NUMERIC without precision falls back to Decimal(38,0) with a diagnostic code") {
            val mapped = SqlTypeMapper.map(cols.getValue("l").colDataType)
            mapped.type shouldBe ErmDataType.Decimal(38, 0)
            mapped.diagnosticCode shouldBe "REV-SQL-010"
        }

        test("REAL / DOUBLE PRECISION") {
            SqlTypeMapper.map(cols.getValue("m").colDataType).type shouldBe ErmDataType.Real(double = false)
            SqlTypeMapper.map(cols.getValue("n").colDataType).type shouldBe ErmDataType.Real(double = true)
        }

        test("VARCHAR / CHARACTER VARYING with length") {
            SqlTypeMapper.map(cols.getValue("o").colDataType).type shouldBe ErmDataType.Varchar(255)
            SqlTypeMapper.map(cols.getValue("p").colDataType).type shouldBe ErmDataType.Varchar(50)
        }

        test("CHAR maps to Varchar with a lossy-mapping diagnostic") {
            val mapped = SqlTypeMapper.map(cols.getValue("q").colDataType)
            mapped.type shouldBe ErmDataType.Varchar(3)
            mapped.diagnosticCode shouldBe "REV-SQL-015"
        }

        test("TEXT / BOOLEAN / BOOL") {
            SqlTypeMapper.map(cols.getValue("r").colDataType).type shouldBe ErmDataType.Text
            SqlTypeMapper.map(cols.getValue("s").colDataType).type shouldBe ErmDataType.Boolean
            SqlTypeMapper.map(cols.getValue("t2").colDataType).type shouldBe ErmDataType.Boolean
        }

        test("DATE / TIME") {
            SqlTypeMapper.map(cols.getValue("u").colDataType).type shouldBe ErmDataType.Date
            SqlTypeMapper.map(cols.getValue("v").colDataType).type shouldBe ErmDataType.Time
        }

        test("TIMESTAMP variants") {
            SqlTypeMapper.map(cols.getValue("w").colDataType).type shouldBe ErmDataType.Timestamp(withTimeZone = false)
            SqlTypeMapper.map(cols.getValue("x").colDataType).type shouldBe ErmDataType.Timestamp(withTimeZone = true)
            SqlTypeMapper.map(cols.getValue("y").colDataType).type shouldBe ErmDataType.Timestamp(withTimeZone = false)
            SqlTypeMapper.map(cols.getValue("z").colDataType).type shouldBe ErmDataType.Timestamp(withTimeZone = true)
        }

        test("UUID / BYTEA / JSON / JSONB") {
            SqlTypeMapper.map(cols.getValue("aa").colDataType).type shouldBe ErmDataType.Uuid
            SqlTypeMapper.map(cols.getValue("bb").colDataType).type shouldBe ErmDataType.Blob
            SqlTypeMapper.map(cols.getValue("cc").colDataType).type shouldBe ErmDataType.Json
            SqlTypeMapper.map(cols.getValue("dd").colDataType).type shouldBe ErmDataType.Json
        }

        test("unknown type maps to Custom with a diagnostic code") {
            val mapped = SqlTypeMapper.map(cols.getValue("ee").colDataType)
            mapped.type shouldBe ErmDataType.Custom("TSVECTOR")
            mapped.diagnosticCode shouldBe "REV-SQL-010"
        }
    })
