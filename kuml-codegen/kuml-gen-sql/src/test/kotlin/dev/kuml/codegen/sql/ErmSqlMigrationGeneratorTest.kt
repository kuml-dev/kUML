package dev.kuml.codegen.sql

import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * ADR-0016 (deferred item) — file-writing/naming coverage for
 * [ErmSqlMigrationGenerator]. Diff-decision coverage lives in
 * [ErmSchemaDiffGeneratorTest]; this class only exercises the
 * validate → diff → write orchestration and Flyway naming/path-traversal
 * guards (shared with [FlywayFileNaming], already covered end-to-end by
 * [FlywayBaselineGeneratorTest]).
 */
class ErmSqlMigrationGeneratorTest :
    FunSpec({

        fun tempDir(): File = Files.createTempDirectory("kuml-sql-migration-test").toFile()

        fun oldModel() =
            ermModel(name = "D") {
                entity(name = "users") { id(name = "id", type = ErmDataType.Integer(64)) }
            }

        fun newModel() =
            ermModel(name = "D") {
                entity(name = "users") {
                    id(name = "id", type = ErmDataType.Integer(64))
                    attribute(name = "nickname", type = ErmDataType.Varchar(255), nullable = true)
                }
            }

        test("produces exactly V<version>__<description>.sql") {
            val out = tempDir()
            try {
                val file =
                    ErmSqlMigrationGenerator().generate(
                        old = oldModel(),
                        new = newModel(),
                        outputDir = out,
                        version = "2",
                        description = "add_nickname",
                        options = emptyMap(),
                    )
                file.name shouldBe "V2__add_nickname.sql"
                file.readText() shouldContain "ALTER TABLE users ADD COLUMN nickname VARCHAR(255) NULL;"
            } finally {
                out.deleteRecursively()
            }
        }

        test("invalid version rejects via IllegalArgumentException, path-traversal guard intact") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    ErmSqlMigrationGenerator().generate(
                        old = oldModel(),
                        new = newModel(),
                        outputDir = out,
                        version = "../../../../../../tmp/evil",
                        description = "add_nickname",
                        options = emptyMap(),
                    )
                }
                out.listFiles().orEmpty().map { it.name } shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("invalid description rejects via IllegalArgumentException") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    ErmSqlMigrationGenerator().generate(
                        old = oldModel(),
                        new = newModel(),
                        outputDir = out,
                        version = "1",
                        description = "sub/evil",
                        options = emptyMap(),
                    )
                }
                out.listFiles().orEmpty().map { it.name } shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("a refused (destructive) diff writes no file into outputDir") {
            val out = tempDir()
            try {
                val old =
                    ermModel(name = "D") {
                        entity(name = "users") { id(name = "id", type = ErmDataType.Integer(64)) }
                        entity(name = "legacy") { id(name = "id", type = ErmDataType.Integer(64)) }
                    }
                val new =
                    ermModel(name = "D") {
                        entity(name = "users") { id(name = "id", type = ErmDataType.Integer(64)) }
                    }
                shouldThrow<CodeGenerationException> {
                    ErmSqlMigrationGenerator().generate(
                        old = old,
                        new = new,
                        outputDir = out,
                        version = "1",
                        description = "drop_legacy",
                        options = emptyMap(),
                    )
                }
                out.listFiles().orEmpty().toList() shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("an empty diff (identical models) refuses with CodeGenerationException and writes no file") {
            val out = tempDir()
            try {
                val model = oldModel()
                shouldThrow<CodeGenerationException> {
                    ErmSqlMigrationGenerator().generate(
                        old = model,
                        new = model,
                        outputDir = out,
                        version = "1",
                        description = "noop",
                        options = emptyMap(),
                    )
                }
                out.listFiles().orEmpty().toList() shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("a model failing ErmConstraintChecker validation refuses before computing a diff") {
            val out = tempDir()
            try {
                val broken =
                    ermModel(name = "D") {
                        entity(name = "broken") {
                            attribute(name = "name", type = ErmDataType.Varchar(255))
                        }
                    }
                shouldThrow<CodeGenerationException> {
                    ErmSqlMigrationGenerator().generate(
                        old = broken,
                        new = newModel(),
                        outputDir = out,
                        version = "1",
                        description = "x",
                        options = emptyMap(),
                    )
                }
                out.listFiles().orEmpty().toList() shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("dialect option is honoured") {
            val out = tempDir()
            try {
                val old = ermModel(name = "D") { entity(name = "users") { id(name = "id", type = ErmDataType.Integer(64)) } }
                val new =
                    ermModel(name = "D") {
                        entity(name = "users") {
                            id(name = "id", type = ErmDataType.Integer(64))
                            attribute(name = "active", type = ErmDataType.Boolean, nullable = false, default = "true")
                        }
                    }
                val file =
                    ErmSqlMigrationGenerator().generate(
                        old = old,
                        new = new,
                        outputDir = out,
                        version = "1",
                        description = "add_active",
                        options =
                            mapOf(
                                "sql-dialect" to "mysql",
                            ),
                    )
                file.readText() shouldContain "TINYINT(1)"
            } finally {
                out.deleteRecursively()
            }
        }
    })
