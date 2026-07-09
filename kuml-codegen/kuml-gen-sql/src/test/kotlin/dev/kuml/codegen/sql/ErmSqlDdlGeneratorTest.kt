package dev.kuml.codegen.sql

import dev.kuml.codegen.api.ErmCodeGenRegistry
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * V3.4.7 — ERM-first entry point [ErmSqlDdlGenerator] and its
 * [ErmCodeGenRegistry] registration.
 */
class ErmSqlDdlGeneratorTest :
    FunSpec({

        test("generates schema.sql from an ErmModel") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("email", ErmDataType.Varchar(255), nullable = false)
                    }
                }
            val out = Files.createTempDirectory("kuml-erm-sql-test").toFile()
            try {
                val files = ErmSqlDdlGenerator().generate(model, out, emptyMap())
                files.size shouldBe 1
                files.single().name shouldBe "schema.sql"
                val sql = files.single().readText()
                sql shouldContain "CREATE TABLE users ("
                sql shouldContain "email VARCHAR(255) NOT NULL"
            } finally {
                out.deleteRecursively()
            }
        }

        test("sql-dialect option is honoured") {
            val model =
                ermModel("M") {
                    entity("users") {
                        attribute("id", ErmDataType.Integer(64), primaryKey = true, nullable = false, autoIncrement = true)
                    }
                }
            val out = Files.createTempDirectory("kuml-erm-sql-test").toFile()
            try {
                val files = ErmSqlDdlGenerator().generate(model, out, mapOf("sql-dialect" to "mysql"))
                files.single().readText() shouldContain "AUTO_INCREMENT"
            } finally {
                out.deleteRecursively()
            }
        }

        test("provider is exposed as 'sql' generator id in the ErmCodeGenRegistry") {
            ErmSqlDdlGeneratorProvider().generator().id shouldBe "sql"
        }

        test("loadFromClasspath discovers the bundled ERM sql generator(s)") {
            ErmCodeGenRegistry.clear()
            ErmCodeGenRegistry.loadFromClasspath()
            ErmCodeGenRegistry.names() shouldContain "sql"
            ErmCodeGenRegistry.names() shouldContain "sql-flyway-baseline"
        }
    })
