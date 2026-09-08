package dev.kuml.workspace.scaffold

import dev.kuml.workspace.OkfValidator
import dev.kuml.workspace.WorkspaceMode
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

/**
 * Tests for [WorkspaceInitSpec] and [WorkspaceScaffolder], moved here (V3.x,
 * FT-Desktop-New-Workspace) from `dev.kuml.cli.workspace.WorkspaceInitCommandTest`'s
 * `context("WorkspaceInitSpec.slugify")` and `context("WorkspaceScaffolder.templateFiles")`
 * blocks — those were removed there to avoid duplication (the CLI-level test file keeps only
 * the `KumlCli().test(...)` command-integration tests, which exercise this module indirectly).
 */
class WorkspaceScaffolderTest :
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

        // ── WorkspaceInitSpec.escapeKotlinStringLiteral ──────────────────────

        context("WorkspaceInitSpec.escapeKotlinStringLiteral") {
            test("escapes a single backslash into two") {
                WorkspaceInitSpec.escapeKotlinStringLiteral("a\\b") shouldBe "a\\\\b"
            }

            test("escapes a double quote") {
                WorkspaceInitSpec.escapeKotlinStringLiteral("a\"b") shouldBe "a\\\"b"
            }

            test("escapes a dollar sign") {
                WorkspaceInitSpec.escapeKotlinStringLiteral("a\$b") shouldBe "a\\\$b"
            }

            test("escapes newline, carriage return and tab") {
                WorkspaceInitSpec.escapeKotlinStringLiteral("a\nb\rc\td") shouldBe "a\\nb\\rc\\td"
            }

            test("leaves a plain string unchanged") {
                WorkspaceInitSpec.escapeKotlinStringLiteral("Muster Verein") shouldBe "Muster Verein"
            }
        }

        // ── WorkspaceScaffolder.templateFiles — resource existence guard ────────

        context("WorkspaceScaffolder.templateFiles") {
            test("knowledge template resources all load from the classpath") {
                val tmpDir = tempDir("kuml-ws-templates-knowledge")
                val spec = WorkspaceInitSpec.from(name = "Test", mode = "knowledge")
                WorkspaceScaffolder.scaffold(spec = spec, targetDir = tmpDir, force = true, echo = {})
                WorkspaceScaffolder.templateFiles("knowledge").size shouldBe 5
                tmpDir.deleteRecursively()
            }

            test("engineering template resources all load from the classpath") {
                val tmpDir = tempDir("kuml-ws-templates-engineering")
                val spec = WorkspaceInitSpec.from(name = "Test", mode = "engineering")
                WorkspaceScaffolder.scaffold(spec = spec, targetDir = tmpDir, force = true, echo = {})
                WorkspaceScaffolder.templateFiles("engineering").size shouldBe 3
                tmpDir.deleteRecursively()
            }
        }

        // ── End-to-end: scaffold → scan → validate ───────────────────────────

        context("WorkspaceScaffolder.scaffold — end-to-end") {
            test("knowledge mode creates the exact 5-file set, scans as KNOWLEDGE and validates clean") {
                val tmpDir = tempDir("kuml-ws-e2e-knowledge")
                try {
                    val spec = WorkspaceInitSpec.from(name = "Muster Verein", mode = "knowledge")
                    WorkspaceScaffolder.scaffold(spec = spec, targetDir = tmpDir, force = false, echo = {})

                    val created =
                        tmpDir
                            .walkTopDown()
                            .filter { it.isFile }
                            .map { it.relativeTo(tmpDir).path.replace(File.separatorChar, '/') }
                            .toSet()
                    created shouldContainExactlyInAnyOrder
                        setOf(
                            ".kuml-workspace.toml",
                            "index.md",
                            "articles/01-introduction.md",
                            "models/domain-classes.md",
                            "glossary/index.md",
                        )

                    val ws = WorkspaceScanner.scan(root = tmpDir)
                    ws.mode shouldBe WorkspaceMode.KNOWLEDGE
                    OkfValidator.validate(ws = ws) shouldBe emptyList()
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("engineering mode creates the exact 3-file set and scans as ENGINEERING") {
                val tmpDir = tempDir("kuml-ws-e2e-engineering")
                try {
                    val spec = WorkspaceInitSpec.from(name = "My Diagrams", mode = "engineering")
                    WorkspaceScaffolder.scaffold(spec = spec, targetDir = tmpDir, force = false, echo = {})

                    val created =
                        tmpDir
                            .walkTopDown()
                            .filter { it.isFile }
                            .map { it.relativeTo(tmpDir).path.replace(File.separatorChar, '/') }
                            .toSet()
                    created shouldContainExactlyInAnyOrder setOf(".kuml-workspace.toml", "my-diagrams.kuml.kts", ".gitignore")

                    WorkspaceScanner.scan(root = tmpDir).mode shouldBe WorkspaceMode.ENGINEERING
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("no unresolved {{ }} tokens in any generated file (either mode)") {
                for (mode in listOf("knowledge", "engineering")) {
                    val tmpDir = tempDir("kuml-ws-e2e-tokens-$mode")
                    try {
                        val spec = WorkspaceInitSpec.from(name = "Token Check", mode = mode)
                        WorkspaceScaffolder.scaffold(spec = spec, targetDir = tmpDir, force = false, echo = {})
                        tmpDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            file.readText() shouldNotContain "{{"
                        }
                    } finally {
                        tmpDir.deleteRecursively()
                    }
                }
            }
        }
    })
