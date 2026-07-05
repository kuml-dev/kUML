package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class ExportCommandCliTest :
    FunSpec({

        // The CLI works on the kuml-tests example so we don't have to build a script in this module.
        val sample = File("../kuml-tests/examples/c4/system-context/internet-banking.kuml.kts")

        test("kuml export --format structurizr writes a Structurizr DSL file") {
            if (!sample.exists()) {
                // Guard for CI configurations that don't have this sibling module on disk.
                return@test
            }
            val out = Files.createTempFile("kuml-export-", ".dsl").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("export", "--format", "structurizr", sample.absolutePath, "-o", out.absolutePath),
                    )
                result.statusCode shouldBe 0
                val text = out.readText()
                text shouldContain "workspace"
                text shouldContain "model {"
                text shouldContain "views {"
                text shouldContain "person"
                text shouldContain "softwareSystem"
            } finally {
                out.delete()
            }
        }

        test("kuml export rejects UML scripts with a clear error") {
            // Inline-Skript ohne C4: ein minimales classDiagram im Test-Resource-Ordner.
            val tmpDir = Files.createTempDirectory("kuml-export-uml-")
            val script =
                tmpDir.resolve("uml-only.kuml.kts").toFile().apply {
                    writeText(
                        """
                        @file:Suppress("unused")

                        classDiagram(name = "Just UML") {
                            classOf("Foo")
                        }
                        """.trimIndent(),
                    )
                }
            try {
                val result =
                    KumlCli().test(
                        listOf("export", "--format", "structurizr", script.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
            } finally {
                script.delete()
                tmpDir.toFile().delete()
            }
        }
    })
