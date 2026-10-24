package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class GenerateCommandMultiScriptTest :
    FunSpec({

        val root = File("src/test/resources/multi-script")

        test("several -i scripts generate one CREATE TABLE per table") {
            val out = Files.createTempDirectory("kuml-generate-multi").toFile()
            try {
                val args =
                    listOf("generate") +
                        listOf("split/20-tags.kuml.kts", "split/00-members.kuml.kts", "split/10-orders.kuml.kts")
                            .flatMap { listOf("-i", File(root, it).absolutePath) } +
                        listOf("--plugin", "sql", "-o", out.absolutePath)
                val result = KumlCli().test(args)
                result.statusCode shouldBe 0
                result.output shouldContain "Merged 3 script(s)"
                val sql = out.walkTopDown().filter { it.isFile && it.extension == "sql" }.joinToString("\n") { it.readText() }
                listOf("member", "customer_order", "tag").forEach { table ->
                    Regex("CREATE TABLE (IF NOT EXISTS )?\"?$table\"?\\b", RegexOption.IGNORE_CASE).findAll(sql).count() shouldBe 1
                }
            } finally {
                out.deleteRecursively()
            }
        }

        test("a merge conflict exits with MODEL_MERGE_CONFLICT") {
            val out = Files.createTempDirectory("kuml-generate-multi-conflict").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "generate",
                            "-i",
                            File(root, "conflict/a.kuml.kts").absolutePath,
                            "-i",
                            File(root, "conflict/b.kuml.kts").absolutePath,
                            "--plugin",
                            "sql",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe ExitCodes.MODEL_MERGE_CONFLICT
            } finally {
                out.deleteRecursively()
            }
        }

        test("cross-script stubs of plain classes are merged instead of overwriting the full declaration") {
            val out = Files.createTempDirectory("kuml-generate-multi-domain").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("generate") +
                            listOf("domain/b.kuml.kts", "domain/a.kuml.kts").flatMap { listOf("-i", File(root, it).absolutePath) } +
                            listOf("--plugin", "kotlin", "-o", out.absolutePath),
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "2 file(s) generated"
                val files = out.walkTopDown().filter { it.isFile }.toList()
                files.map { it.name }.sorted() shouldBe listOf("Address.kt", "Customer.kt")
                files.first { it.name == "Address.kt" }.readText() shouldContain "city"
            } finally {
                out.deleteRecursively()
            }
        }

        test("irreconcilable plain-class declarations exit with MODEL_MERGE_CONFLICT") {
            val out = Files.createTempDirectory("kuml-generate-multi-domain-conflict").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("generate") +
                            listOf("domain-conflict/a.kuml.kts", "domain-conflict/b.kuml.kts")
                                .flatMap { listOf("-i", File(root, it).absolutePath) } +
                            listOf("--plugin", "kotlin", "-o", out.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.MODEL_MERGE_CONFLICT
                result.stderr shouldContain "zip"
            } finally {
                out.deleteRecursively()
            }
        }

        test("a stereotype-less stub of an entity does not drop the entity's attributes") {
            val out = Files.createTempDirectory("kuml-generate-multi-entity-stub").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("generate") +
                            listOf("entity-stub/a.kuml.kts", "entity-stub/b.kuml.kts")
                                .flatMap { listOf("-i", File(root, it).absolutePath) } +
                            listOf("--plugin", "kotlin", "-o", out.absolutePath),
                    )
                result.statusCode shouldBe 0
                val files = out.walkTopDown().filter { it.isFile }.toList()
                files.map { it.name }.sorted() shouldBe listOf("Address.kt", "Customer.kt")
                files.first { it.name == "Address.kt" }.readText() shouldContain "city"
            } finally {
                out.deleteRecursively()
            }
        }

        test("the same generalization in two scripts yields a single supertype") {
            val out = Files.createTempDirectory("kuml-generate-multi-inherit").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("generate") +
                            listOf("inherit/a.kuml.kts", "inherit/b.kuml.kts")
                                .flatMap { listOf("-i", File(root, it).absolutePath) } +
                            listOf("--plugin", "kotlin", "-o", out.absolutePath),
                    )
                result.statusCode shouldBe 0
                val customer = out.walkTopDown().first { it.name == "Customer.kt" }.readText()
                Regex("Person\\(\\)").findAll(customer).count() shouldBe 1
            } finally {
                out.deleteRecursively()
            }
        }
    })
