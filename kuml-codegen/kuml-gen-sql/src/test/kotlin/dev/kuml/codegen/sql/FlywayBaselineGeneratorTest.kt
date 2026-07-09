package dev.kuml.codegen.sql

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.exposed.UmlToExposedPsmTransformer
import dev.kuml.core.model.KumlDiagram
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

@Suppress("DEPRECATION") // UmlToExposedPsmTransformer deprecated V3.4.8 — superseded by uml-to-exposed-via-erm; test kept unmodified.
class FlywayBaselineGeneratorTest :
    FunSpec({

        fun tempDir(): File = Files.createTempDirectory("kuml-flyway-baseline-test").toFile()

        fun simpleDiagram(): KumlDiagram {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "email",
                                name = "email",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            return KumlDiagram(name = "D", elements = listOf(cls))
        }

        test("default options produce V1__init.sql") {
            val out = tempDir()
            try {
                val files = FlywayBaselineGenerator().generate(simpleDiagram(), out, emptyMap())
                files.size shouldBe 1
                files.single().name shouldBe "V1__init.sql"
            } finally {
                out.deleteRecursively()
            }
        }

        test("custom flyway-version and flyway-description are respected") {
            val out = tempDir()
            try {
                val files =
                    FlywayBaselineGenerator().generate(
                        simpleDiagram(),
                        out,
                        mapOf("flyway-version" to "3", "flyway-description" to "add_orders_table"),
                    )
                files.single().name shouldBe "V3__add_orders_table.sql"
            } finally {
                out.deleteRecursively()
            }
        }

        test("content matches what SqlDdlGenerator alone produces for the same diagram+options") {
            val ddlOut = tempDir()
            val flywayOut = tempDir()
            try {
                val diagram = simpleDiagram()
                val options = mapOf("sql-dialect" to "mysql")

                val ddlFiles = SqlDdlGenerator().generate(diagram, ddlOut, options)
                val flywayFiles = FlywayBaselineGenerator().generate(diagram, flywayOut, options)

                flywayFiles.single().readText() shouldBe ddlFiles.single().readText()
            } finally {
                ddlOut.deleteRecursively()
                flywayOut.deleteRecursively()
            }
        }

        test("works with a Wave-A-annotated PSM diagram producing explicit table names") {
            val rawCls =
                UmlClass(
                    id = "u",
                    name = "AuthUser",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "email",
                                name = "email",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val rawDiagram = KumlDiagram(name = "D", elements = listOf(rawCls))

            val result = UmlToExposedPsmTransformer().transform(rawDiagram, TransformContext())
            val psmDiagram =
                when (result) {
                    is TransformResult.Success -> result.output
                    is TransformResult.Failure -> error("PSM transform failed: ${result.errors}")
                }

            val out = tempDir()
            try {
                val files = FlywayBaselineGenerator().generate(psmDiagram, out, emptyMap())
                val sql = files.single().readText()
                // toSnakeCase("AuthUser") -> "auth_user", pluralized -> "auth_users"
                sql shouldContain "CREATE TABLE auth_users ("
            } finally {
                out.deleteRecursively()
            }
        }

        test("output dir contains only the migration file, no leftover schema.sql or scratch directory") {
            val out = tempDir()
            try {
                FlywayBaselineGenerator().generate(simpleDiagram(), out, emptyMap())
                val names =
                    out
                        .listFiles()
                        ?.map { it.name }
                        ?.sorted()
                        .orEmpty()
                names shouldBe listOf("V1__init.sql")
            } finally {
                out.deleteRecursively()
            }
        }

        test("rejects path traversal via flyway-version") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    FlywayBaselineGenerator().generate(
                        simpleDiagram(),
                        out,
                        mapOf("flyway-version" to "../../../../../../tmp/evil"),
                    )
                }
                out.listFiles().orEmpty().map { it.name } shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("rejects path traversal via flyway-description") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    FlywayBaselineGenerator().generate(
                        simpleDiagram(),
                        out,
                        mapOf("flyway-description" to "../../../../../../tmp/evil"),
                    )
                }
            } finally {
                out.deleteRecursively()
            }
        }

        test("rejects flyway-description containing a slash even without '..'") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    FlywayBaselineGenerator().generate(
                        simpleDiagram(),
                        out,
                        mapOf("flyway-description" to "sub/evil"),
                    )
                }
            } finally {
                out.deleteRecursively()
            }
        }

        test("rejects flyway-version containing non-numeric characters") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    FlywayBaselineGenerator().generate(
                        simpleDiagram(),
                        out,
                        mapOf("flyway-version" to "1;rm -rf"),
                    )
                }
            } finally {
                out.deleteRecursively()
            }
        }

        test("provider is exposed as 'sql-flyway-baseline' generator id") {
            FlywayBaselineGeneratorProvider().generator().id shouldBe "sql-flyway-baseline"
        }

        test("delegates to injected KumlCodeGenerator instead of always constructing SqlDdlGenerator") {
            class FakeGenerator : KumlCodeGenerator {
                override val id: String = "fake"
                override val displayName: String = "Fake"

                override fun generate(
                    diagram: KumlDiagram,
                    outputDir: File,
                    options: Map<String, String>,
                ): List<File> {
                    outputDir.mkdirs()
                    val f = File(outputDir, "fake-schema.sql")
                    f.writeText("-- fake content")
                    return listOf(f)
                }
            }

            val out = tempDir()
            try {
                val files = FlywayBaselineGenerator(FakeGenerator()).generate(simpleDiagram(), out, emptyMap())
                files.single().name shouldBe "V1__init.sql"
                files.single().readText() shouldBe "-- fake content"
            } finally {
                out.deleteRecursively()
            }
        }

        // ── V3.4.7 — ERM-first counterpart ──────────────────────────────────────

        fun simpleErmModel() =
            ermModel("D") {
                entity("users") {
                    id("id", ErmDataType.Integer(64))
                    attribute("email", ErmDataType.Varchar(255), nullable = false)
                }
            }

        test("ERM-first: default options produce V1__init.sql") {
            val out = tempDir()
            try {
                val files = ErmFlywayBaselineGenerator().generate(simpleErmModel(), out, emptyMap())
                files.size shouldBe 1
                files.single().name shouldBe "V1__init.sql"
                files.single().readText() shouldContain "CREATE TABLE users ("
            } finally {
                out.deleteRecursively()
            }
        }

        test("ERM-first: custom flyway-version and flyway-description are respected") {
            val out = tempDir()
            try {
                val files =
                    ErmFlywayBaselineGenerator().generate(
                        simpleErmModel(),
                        out,
                        mapOf("flyway-version" to "3", "flyway-description" to "add_orders_table"),
                    )
                files.single().name shouldBe "V3__add_orders_table.sql"
            } finally {
                out.deleteRecursively()
            }
        }

        test("ERM-first: output dir contains only the migration file") {
            val out = tempDir()
            try {
                ErmFlywayBaselineGenerator().generate(simpleErmModel(), out, emptyMap())
                val names =
                    out
                        .listFiles()
                        ?.map { it.name }
                        ?.sorted()
                        .orEmpty()
                names shouldBe listOf("V1__init.sql")
            } finally {
                out.deleteRecursively()
            }
        }

        test("ERM-first: rejects path traversal via flyway-version") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    ErmFlywayBaselineGenerator().generate(
                        simpleErmModel(),
                        out,
                        mapOf("flyway-version" to "../../../../../../tmp/evil"),
                    )
                }
                out.listFiles().orEmpty().map { it.name } shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("ERM-first: rejects flyway-description containing a slash even without '..'") {
            val out = tempDir()
            try {
                shouldThrow<IllegalArgumentException> {
                    ErmFlywayBaselineGenerator().generate(
                        simpleErmModel(),
                        out,
                        mapOf("flyway-description" to "sub/evil"),
                    )
                }
            } finally {
                out.deleteRecursively()
            }
        }

        test("ERM-first provider is exposed as 'sql-flyway-baseline' generator id") {
            ErmFlywayBaselineGeneratorProvider().generator().id shouldBe "sql-flyway-baseline"
        }
    })
