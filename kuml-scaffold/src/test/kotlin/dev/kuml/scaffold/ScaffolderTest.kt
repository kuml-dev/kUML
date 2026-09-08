package dev.kuml.scaffold

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

/**
 * Tests for the generic scaffold engine ([TemplateEngine], [Scaffolder]), moved here
 * (V3.x, FT-Desktop-New-Workspace) from `dev.kuml.cli.plugin.PluginInitCommandTest`'s
 * `context("TemplateEngine")` block. That block is intentionally left in place there too
 * (small, deliberate duplication — see the module's KDoc), so these tests additionally cover
 * [Scaffolder] itself, which had no dedicated unit test before this module existed (it was
 * only exercised indirectly through `PluginScaffolder`/`WorkspaceScaffolder` callers).
 */
class ScaffolderTest :
    FunSpec({

        context("TemplateEngine") {
            test("replaces known tokens") {
                val result = TemplateEngine.render(template = "Hello {{name}}!", vars = mapOf("name" to "kUML"))
                result shouldBe "Hello kUML!"
            }

            test("throws on unknown token") {
                val ex = runCatching { TemplateEngine.render(template = "{{unknown}}", vars = emptyMap()) }
                ex.isFailure shouldBe true
            }

            test("leaves no unresolved {{}} in rendered output when all vars provided") {
                val template = "{{a}} {{b}}"
                TemplateEngine.render(template = template, vars = mapOf("a" to "1", "b" to "2")) shouldNotContain "{{"
            }
        }

        context("Scaffolder.scaffold") {
            test("renders {{var}} tokens in both content and output path") {
                val tmpDir = Files.createTempDirectory("kuml-scaffold-test").toFile()
                try {
                    Scaffolder.scaffold(
                        templates = listOf(TemplateFile(resourcePath = "test-templates/{{slug}}.txt.tmpl", outputPath = "{{slug}}.txt")),
                        vars = mapOf("slug" to "greeting", "name" to "World"),
                        targetDir = tmpDir,
                        force = false,
                        echo = {},
                    )
                    val out = File(tmpDir, "greeting.txt")
                    out.exists() shouldBe true
                    out.readText().trim() shouldBe "Hello World!"
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("throws IllegalStateException for a missing classpath resource") {
                val tmpDir = Files.createTempDirectory("kuml-scaffold-missing-test").toFile()
                try {
                    val result =
                        runCatching {
                            Scaffolder.scaffold(
                                templates =
                                    listOf(
                                        TemplateFile(resourcePath = "test-templates/does-not-exist.tmpl", outputPath = "out.txt"),
                                    ),
                                vars = emptyMap(),
                                targetDir = tmpDir,
                                force = false,
                                echo = {},
                            )
                        }
                    result.isFailure shouldBe true
                    result.exceptionOrNull() shouldNotBe null
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("throws IllegalStateException for a non-empty target directory without force") {
                val tmpDir = Files.createTempDirectory("kuml-scaffold-noforce-test").toFile()
                try {
                    File(tmpDir, "existing.txt").writeText("content")
                    val result =
                        runCatching {
                            Scaffolder.scaffold(
                                templates = listOf(TemplateFile(resourcePath = "test-templates/{{slug}}.txt.tmpl", outputPath = "out.txt")),
                                vars = mapOf("slug" to "x", "name" to "X"),
                                targetDir = tmpDir,
                                force = false,
                                echo = {},
                            )
                        }
                    result.isFailure shouldBe true
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("force = true overwrites a non-empty target directory") {
                val tmpDir = Files.createTempDirectory("kuml-scaffold-force-test").toFile()
                try {
                    File(tmpDir, "existing.txt").writeText("content")
                    Scaffolder.scaffold(
                        templates = listOf(TemplateFile(resourcePath = "test-templates/{{slug}}.txt.tmpl", outputPath = "out.txt")),
                        vars = mapOf("slug" to "x", "name" to "X"),
                        targetDir = tmpDir,
                        force = true,
                        echo = {},
                    )
                    File(tmpDir, "out.txt").exists() shouldBe true
                } finally {
                    tmpDir.deleteRecursively()
                }
            }
        }
    })
