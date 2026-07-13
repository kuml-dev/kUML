package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.erm.model.ErmDataType
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.enumOf
import dev.kuml.uml.dsl.literal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UmlToErmTypeMappingTest :
    FunSpec({

        test("string/str map to Varchar(255)") {
            UmlErmTypeMapper.map("string") shouldBe ErmDataType.Varchar(255)
            UmlErmTypeMapper.map("Str") shouldBe ErmDataType.Varchar(255)
        }

        test("text/clob map to Text") {
            UmlErmTypeMapper.map("text") shouldBe ErmDataType.Text
            UmlErmTypeMapper.map("CLOB") shouldBe ErmDataType.Text
        }

        test("int/integer map to Integer(32)") {
            UmlErmTypeMapper.map("int") shouldBe ErmDataType.Integer(32)
            UmlErmTypeMapper.map("Integer") shouldBe ErmDataType.Integer(32)
        }

        test("long/bigint map to Integer(64)") {
            UmlErmTypeMapper.map("long") shouldBe ErmDataType.Integer(64)
            UmlErmTypeMapper.map("BigInt") shouldBe ErmDataType.Integer(64)
        }

        test("short maps to Integer(16)") {
            UmlErmTypeMapper.map("short") shouldBe ErmDataType.Integer(16)
        }

        test("boolean/bool map to Boolean") {
            UmlErmTypeMapper.map("boolean") shouldBe ErmDataType.Boolean
            UmlErmTypeMapper.map("Bool") shouldBe ErmDataType.Boolean
        }

        test("double/float/real map to Real(double=true)") {
            UmlErmTypeMapper.map("double") shouldBe ErmDataType.Real(true)
            UmlErmTypeMapper.map("float") shouldBe ErmDataType.Real(true)
            UmlErmTypeMapper.map("real") shouldBe ErmDataType.Real(true)
        }

        test("decimal/bigdecimal/money map to Decimal(19,2)") {
            UmlErmTypeMapper.map("decimal") shouldBe ErmDataType.Decimal(19, 2)
            UmlErmTypeMapper.map("BigDecimal") shouldBe ErmDataType.Decimal(19, 2)
            UmlErmTypeMapper.map("money") shouldBe ErmDataType.Decimal(19, 2)
        }

        test("uuid maps to Uuid") {
            UmlErmTypeMapper.map("UUID") shouldBe ErmDataType.Uuid
        }

        test("date/localdate map to Date") {
            UmlErmTypeMapper.map("date") shouldBe ErmDataType.Date
            UmlErmTypeMapper.map("LocalDate") shouldBe ErmDataType.Date
        }

        test("time/localtime map to Time") {
            UmlErmTypeMapper.map("time") shouldBe ErmDataType.Time
            UmlErmTypeMapper.map("LocalTime") shouldBe ErmDataType.Time
        }

        test("datetime/timestamp/instant/localdatetime map to Timestamp(withTimeZone=false)") {
            UmlErmTypeMapper.map("datetime") shouldBe ErmDataType.Timestamp(false)
            UmlErmTypeMapper.map("timestamp") shouldBe ErmDataType.Timestamp(false)
            UmlErmTypeMapper.map("Instant") shouldBe ErmDataType.Timestamp(false)
            UmlErmTypeMapper.map("LocalDateTime") shouldBe ErmDataType.Timestamp(false)
        }

        test("offsetdatetime/zoneddatetime map to Timestamp(withTimeZone=true)") {
            UmlErmTypeMapper.map("OffsetDateTime") shouldBe ErmDataType.Timestamp(true)
            UmlErmTypeMapper.map("ZonedDateTime") shouldBe ErmDataType.Timestamp(true)
        }

        test("blob/bytearray map to Blob") {
            UmlErmTypeMapper.map("blob") shouldBe ErmDataType.Blob
            UmlErmTypeMapper.map("ByteArray") shouldBe ErmDataType.Blob
        }

        test("json/jsonb map to Json") {
            UmlErmTypeMapper.map("json") shouldBe ErmDataType.Json
            UmlErmTypeMapper.map("JSONB") shouldBe ErmDataType.Json
        }

        test("unknown type falls back to Custom") {
            UmlErmTypeMapper.map("SomeVendorType") shouldBe ErmDataType.Custom("SomeVendorType")
        }

        test("mapOverride prefers the raw override string when it doesn't parse to a known type") {
            UmlErmTypeMapper.mapOverride("tsvector") shouldBe ErmDataType.Custom("tsvector")
        }

        test("mapOverride still maps known type names") {
            UmlErmTypeMapper.mapOverride("uuid") shouldBe ErmDataType.Uuid
        }

        test("mapOverride of a blank string falls back to Custom holding the blank string") {
            UmlErmTypeMapper.mapOverride("") shouldBe ErmDataType.Custom("")
        }

        test("UML enum attribute maps to ErmDataType.Enum plus a matching CHECK constraint") {
            val transformer = UmlToErmTransformer()
            val diagram =
                classDiagram("D") {
                    val status =
                        enumOf("Status") {
                            literal("Active")
                            literal("Inactive")
                        }
                    classOf("User") {
                        attribute("id", "UUID")
                        attribute("status", status)
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            val model = (result as TransformResult.Success).output
            val entity = model.entities.first()
            val statusCol = entity.attributeByName("status")!!

            statusCol.type shouldBe ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive"))

            val check = entity.checks.single()
            check.expression shouldBe "status IN ('Active', 'Inactive')"
        }
    })
