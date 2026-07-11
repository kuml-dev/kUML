package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlCli
import dev.kuml.workspace.OkfValidator
import dev.kuml.workspace.WorkspaceMode
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class WorkspaceInitCommandTest :
    FunSpec({

        fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

        // ── WorkspaceInitSpec.slugify ────────────────────────────────────────

        context("WorkspaceInitSpec.slugify") {
            test("lowercases and collapses spaces/punctuation to a single dash") {
                WorkspaceInitSpec.slugify("My Club Bylaws") shouldBe "my-club-bylaws"
            }

            test("collapses unicode/punctuation runs") {
                WorkspaceInitSpec.slugify("Müster   Verein!!!") shouldBe "m-ster-verein"
            }

            test("trims leading and trailing dashes") {
                WorkspaceInitSpec.slugify("--Hello--") shouldBe "hello"
            }

            test("falls back to 'workspace' when nothing safe remains") {
                WorkspaceInitSpec.slugify("!!!") shouldBe "workspace"
            }

            test("falls back to 'workspace' for an empty name") {
                WorkspaceInitSpec.slugify("") shouldBe "workspace"
            }
        }

        // ── WorkspaceScaffolder.templateFiles — resource existence guard ────────

        context("WorkspaceScaffolder.templateFiles") {
            test("knowledge template resources all load from the classpath") {
                val tmpDir = tempDir("kuml-ws-templates-knowledge")
                val spec = WorkspaceInitSpec.from("Test", "knowledge")
                WorkspaceScaffolder.scaffold(spec, tmpDir, force = true, echo = {})
                WorkspaceScaffolder.templateFiles("knowledge").size shouldBe 5
                tmpDir.deleteRecursively()
            }

            test("engineering template resources all load from the classpath") {
                val tmpDir = tempDir("kuml-ws-templates-engineering")
                val spec = WorkspaceInitSpec.from("Test", "engineering")
                WorkspaceScaffolder.scaffold(spec, tmpDir, force = true, echo = {})
                WorkspaceScaffolder.templateFiles("engineering").size shouldBe 3
                tmpDir.deleteRecursively()
            }
        }

        // ── CLI integration: knowledge mode ──────────────────────────────────

        context("workspace init CLI — knowledge mode") {
            test("non-interactive happy path exits 0 and creates the exact 5-file set") {
                val tmpDir = tempDir("kuml-ws-cli-knowledge")
                val outputDir = File(tmpDir, "out")
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "knowledge",
                                "--non-interactive",
                                "--name",
                                "Muster Verein",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    result.statusCode shouldBe 0

                    val created =
                        outputDir
                            .walkTopDown()
                            .filter { it.isFile }
                            .map { it.relativeTo(outputDir).path.replace(File.separatorChar, '/') }
                            .toSet()

                    created shouldContainExactlyInAnyOrder
                        setOf(
                            ".kuml-workspace.toml",
                            "index.md",
                            "articles/01-introduction.md",
                            "models/domain-classes.md",
                            "glossary/index.md",
                        )
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("no unresolved {{ }} tokens in any generated file") {
                val tmpDir = tempDir("kuml-ws-cli-knowledge-tokens")
                val outputDir = File(tmpDir, "out")
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "knowledge",
                                "--non-interactive",
                                "--name",
                                "Token Check",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    result.statusCode shouldBe 0

                    outputDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        file.readText() shouldNotContain "{{"
                    }
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("scaffold invariant: validate reports zero findings and render succeeds") {
                val tmpDir = tempDir("kuml-ws-cli-knowledge-invariant")
                val outputDir = File(tmpDir, "out")
                try {
                    val initResult =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "knowledge",
                                "--non-interactive",
                                "--name",
                                "Invariant Check",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    initResult.statusCode shouldBe 0

                    val ws = WorkspaceScanner.scan(outputDir)
                    ws.mode shouldBe WorkspaceMode.KNOWLEDGE
                    ws.documents.size shouldBe 4

                    val findings = OkfValidator.validate(ws)
                    findings shouldBe emptyList()

                    val validateResult =
                        KumlCli().test(listOf("workspace", "validate", outputDir.absolutePath))
                    validateResult.statusCode shouldBe 0

                    val renderResult =
                        KumlCli().test(
                            listOf("workspace", "render", outputDir.absolutePath, "--strict"),
                        )
                    renderResult.statusCode shouldBe 0
                    renderResult.output shouldContain "Rendered 1"
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("name with a double quote, backslash and dollar sign still validates and renders") {
                val tmpDir = tempDir("kuml-ws-cli-knowledge-escaping")
                val outputDir = File(tmpDir, "out")
                try {
                    val initResult =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "knowledge",
                                "--non-interactive",
                                "--name",
                                """My "Cool" Verein \ Price${'$'}Model""",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    initResult.statusCode shouldBe 0

                    val ws = WorkspaceScanner.scan(outputDir)
                    val findings = OkfValidator.validate(ws)
                    findings shouldBe emptyList()

                    val renderResult =
                        KumlCli().test(
                            listOf("workspace", "render", outputDir.absolutePath, "--strict"),
                        )
                    renderResult.statusCode shouldBe 0
                    renderResult.output shouldContain "Rendered 1"
                } finally {
                    tmpDir.deleteRecursively()
                }
            }
        }

        // ── CLI integration: engineering mode ────────────────────────────────

        context("workspace init CLI — engineering mode") {
            test("non-interactive happy path exits 0 and creates the exact 3-file set") {
                val tmpDir = tempDir("kuml-ws-cli-engineering")
                val outputDir = File(tmpDir, "out")
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "engineering",
                                "--non-interactive",
                                "--name",
                                "My Diagrams",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    result.statusCode shouldBe 0

                    val created =
                        outputDir
                            .walkTopDown()
                            .filter { it.isFile }
                            .map { it.relativeTo(outputDir).path.replace(File.separatorChar, '/') }
                            .toSet()

                    created shouldContainExactlyInAnyOrder
                        setOf(".kuml-workspace.toml", "my-diagrams.kuml.kts", ".gitignore")
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("no unresolved {{ }} tokens in any generated file") {
                val tmpDir = tempDir("kuml-ws-cli-engineering-tokens")
                val outputDir = File(tmpDir, "out")
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "engineering",
                                "--non-interactive",
                                "--name",
                                "Token Check",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    result.statusCode shouldBe 0

                    outputDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        file.readText() shouldNotContain "{{"
                    }
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("name with a double quote, backslash and dollar sign still renders via kuml render") {
                val tmpDir = tempDir("kuml-ws-cli-engineering-escaping")
                val outputDir = File(tmpDir, "out")
                try {
                    val initResult =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "engineering",
                                "--non-interactive",
                                "--name",
                                """My "Cool" Verein \ Price${'$'}Model""",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    initResult.statusCode shouldBe 0

                    val scriptFile =
                        outputDir.listFiles { f -> f.name.endsWith(".kuml.kts") }?.singleOrNull()
                            ?: error("Expected exactly one generated .kuml.kts file")
                    val svgFile = File(tmpDir, "out.svg")

                    val renderResult =
                        KumlCli().test(
                            listOf(
                                "render",
                                scriptFile.absolutePath,
                                "--format",
                                "svg",
                                "--output",
                                svgFile.absolutePath,
                            ),
                        )
                    renderResult.statusCode shouldBe 0
                    svgFile.exists() shouldBe true
                } finally {
                    tmpDir.deleteRecursively()
                }
            }
        }

        // ── CLI integration: shared flags ────────────────────────────────────

        context("workspace init CLI — shared flags") {
            test("--non-interactive without --name exits with USAGE (2)") {
                val tmpDir = tempDir("kuml-ws-cli-noname")
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--non-interactive",
                                "--output",
                                tmpDir.absolutePath,
                            ),
                        )
                    result.statusCode shouldBe ExitCodes.USAGE
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("--force overwrites a non-empty target directory") {
                val tmpDir = tempDir("kuml-ws-cli-force")
                val outputDir = File(tmpDir, "out")
                outputDir.mkdirs()
                File(outputDir, "existing.txt").writeText("pre-existing content")
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "knowledge",
                                "--non-interactive",
                                "--name",
                                "Force Check",
                                "--output",
                                outputDir.absolutePath,
                                "--force",
                            ),
                        )
                    result.statusCode shouldBe 0
                    File(outputDir, "index.md").exists() shouldBe true
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("without --force on a non-empty directory exits non-zero and clobbers nothing") {
                val tmpDir = tempDir("kuml-ws-cli-noforce")
                val outputDir = File(tmpDir, "out")
                outputDir.mkdirs()
                File(outputDir, "existing.txt").writeText("pre-existing content")
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "workspace",
                                "init",
                                "--mode",
                                "knowledge",
                                "--non-interactive",
                                "--name",
                                "No Force Check",
                                "--output",
                                outputDir.absolutePath,
                            ),
                        )
                    result.statusCode shouldBe ExitCodes.IO_ERROR
                    File(outputDir, "existing.txt").readText() shouldBe "pre-existing content"
                    File(outputDir, "index.md").exists() shouldBe false
                } finally {
                    tmpDir.deleteRecursively()
                }
            }
        }
    })
